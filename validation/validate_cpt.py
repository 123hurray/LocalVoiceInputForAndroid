import json,time,hashlib,sys
from pathlib import Path
import torch
import transformers.file_utils as fu
# Documentation-only compatibility shim for upstream's transformers 4.4.1 decorators.
fu.add_code_sample_docstrings=lambda *a,**kw:lambda function:function
from transformers import BertTokenizer
from modeling_cpt import CPTForConditionalGeneration

torch.set_num_threads(2)
root=Path(sys.argv[1]) if len(sys.argv)>1 else Path(__file__).parent
start=time.perf_counter()
tokenizer=BertTokenizer.from_pretrained(root/'model',local_files_only=True)
model,loading=CPTForConditionalGeneration.from_pretrained(root/'model',local_files_only=True,output_loading_info=True)
model.eval()
print(json.dumps({'parameters':sum(p.numel() for p in model.parameters()),'load_seconds':round(time.perf_counter()-start,3),'loading':loading},ensure_ascii=False),flush=True)
cases=[
 ('fill_mask','北京是[MASK]的首都'),
 ('fillers_repetition','嗯那个我们我们明天下午开会吧，就是讨论一下这个方案。'),
 ('restart','这个事情也是值得大家应，值得大家注意的。'),
 ('negation','呃，这次不是删除，是暂时停用，我觉得这样可能更合适。'),
 ('mixed','明天下午三点和张伟讨论 Android API 2.0，不要修改 300 元的预算。'),
 ('instruction','请删除多余语气词和重复，保留原意，只输出整理后的句子：嗯那个我们我们明天下午开会吧，就是讨论一下这个方案。')]
results=[]
for kind,source in cases:
 inputs=tokenizer(source,return_tensors='pt')
 start=time.perf_counter()
 with torch.inference_mode():output=model.generate(input_ids=inputs['input_ids'],attention_mask=inputs['attention_mask'],num_beams=4,max_new_tokens=96)
 result={'case':kind,'source':source,'output':tokenizer.decode(output[0],skip_special_tokens=True).replace(' ',''),'seconds':round(time.perf_counter()-start,3),'tokens':len(output[0])}
 results.append(result);print(json.dumps(result,ensure_ascii=False),flush=True)
(root/'results.json').write_text(json.dumps({'revision':'3dbd5b31feca1f7914680e5558b1faa72665433a','modeling_sha256':hashlib.sha256((root/'modeling_cpt.py').read_bytes()).hexdigest(),'loading':loading,'results':results},ensure_ascii=False,indent=2))
