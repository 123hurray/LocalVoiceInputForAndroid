#include <jni.h>
#include <algorithm>
#include "llama.h"
#include <string>
#include <vector>
#include <chrono>
#include <memory>
#include <stdexcept>

// All native calls are serialized on SpeechEngine's worker.
static llama_model * model = nullptr;
static llama_context * context = nullptr;
static std::vector<llama_token> cached_prefix;
struct Stop {
    JNIEnv * env; jobject flag; jmethodID get;
    std::chrono::steady_clock::time_point deadline;
    bool cancelled() { return env->CallBooleanMethod(flag, get) || std::chrono::steady_clock::now() >= deadline; }
};
static bool abort_run(void * data) { return static_cast<Stop *>(data)->cancelled(); }
static bool progress(float, void * data) { return !abort_run(data); }
static void quiet(enum ggml_log_level, const char *, void *) {}
extern "C" JNIEXPORT void JNICALL
Java_com_example_localvoiceime_TextNative_release(JNIEnv *, jobject) {
    if (context) { llama_free(context); context = nullptr; }
    cached_prefix.clear();
    if (model) { llama_model_free(model); model = nullptr; }
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_localvoiceime_TextNative_rewrite(JNIEnv * env, jobject, jstring path, jbyteArray bytes, jobject flag) {
    try {
        Stop stop{env, flag, env->GetMethodID(env->GetObjectClass(flag), "get", "()Z"),
                  std::chrono::steady_clock::now() + std::chrono::seconds(35)};
        llama_log_set(quiet, nullptr);
        if (!model) {
            llama_backend_init();
            auto mp = llama_model_default_params();
            mp.n_gpu_layers = 0; mp.use_mmap = true;
            mp.progress_callback = progress; mp.progress_callback_user_data = &stop;
            const char * location = env->GetStringUTFChars(path, nullptr);
            model = llama_model_load_from_file(location, mp);
            env->ReleaseStringUTFChars(path, location);
            if (!model) throw std::runtime_error("model load failed");
        }
        if (stop.cancelled()) throw std::runtime_error("cancelled");
        auto cp = llama_context_default_params();
        cp.n_ctx = 2048; cp.n_batch = 256; cp.n_ubatch = 128;
        cp.n_threads = 2; cp.n_threads_batch = 2;
        cp.abort_callback = abort_run; cp.abort_callback_data = &stop;
        if (!context) context = llama_init_from_model(model, cp);
        if (!context) throw std::runtime_error("context failed");
        llama_set_abort_callback(context, abort_run, &stop);
        std::string prompt(env->GetArrayLength(bytes), '\0');
        env->GetByteArrayRegion(bytes, 0, prompt.size(), reinterpret_cast<jbyte *>(prompt.data()));
        const auto * vocab = llama_model_get_vocab(model);
        int n = -llama_tokenize(vocab, prompt.data(), prompt.size(), nullptr, 0, false, true);
        if (n <= 0 || n > 1200) throw std::runtime_error("input too long");
        std::vector<llama_token> tokens(n);
        if (llama_tokenize(vocab, prompt.data(), prompt.size(), tokens.data(), n, false, true) != n)
            throw std::runtime_error("tokenization failed");
        // Cache only static instructions/examples; never keep transcript tokens between calls.
        const auto boundary = prompt.rfind("<|im_start|>user\n");
        const int prefix_size = boundary == std::string::npos ? 0 :
            -llama_tokenize(vocab, prompt.data(), boundary, nullptr, 0, false, true);
        const int prefix = std::max(0, std::min(prefix_size, n - 1));
        int start = 0;
        if (prefix > 0 && cached_prefix.size() == static_cast<size_t>(prefix) &&
            std::equal(cached_prefix.begin(), cached_prefix.end(), tokens.begin())) {
            start = prefix;
        } else {
            llama_memory_clear(llama_get_memory(context), true);
            cached_prefix.clear();
        }
        for (int offset = start; offset < n; offset += 256) {
            auto batch = llama_batch_get_one(tokens.data() + offset, std::min(256, n - offset));
            if (stop.cancelled() || llama_decode(context, batch) != 0) throw std::runtime_error("decode failed");
        }
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(llama_sampler_init_greedy(), llama_sampler_free);
        std::string output;
        bool ended = false;
        for (int i = 0; i < 768; ++i) {
            if (stop.cancelled()) throw std::runtime_error("cancelled");
            auto token = llama_sampler_sample(sampler.get(), context, -1);
            if (llama_vocab_is_eog(vocab, token)) { ended = true; break; }
            std::vector<char> piece(256);
            int length = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
            if (length < 0) {
                piece.resize(-length);
                length = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
            }
            if (length < 0) throw std::runtime_error("invalid token");
            output.append(piece.data(), length);
            auto batch = llama_batch_get_one(&token, 1);
            if (llama_decode(context, batch) != 0) throw std::runtime_error("decode failed");
        }
        if (!ended) throw std::runtime_error("output incomplete");
        if (!llama_memory_seq_rm(llama_get_memory(context), -1, prefix, -1))
            throw std::runtime_error("cache reset failed");
        cached_prefix.assign(tokens.begin(), tokens.begin() + prefix);
        llama_set_abort_callback(context, nullptr, nullptr);
        auto result = env->NewByteArray(output.size());
        env->SetByteArrayRegion(result, 0, output.size(), reinterpret_cast<const jbyte *>(output.data()));
        return result;
    } catch (...) {
        if (context) { llama_free(context); context = nullptr; }
        cached_prefix.clear();
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Text processing did not complete");
        return nullptr;
    }
}
