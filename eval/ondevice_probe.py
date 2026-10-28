"""V13: on-device Llama 3.2 1B SpinQuant (.pte) offline Q&A quality probe on Mac.

Same .pte that ships to the phone. Compares free-form English answers with a
sentence-selection mode (model outputs fact numbers, app renders official Korean).

Usage: python -m eval.ondevice_probe
"""

import re, time
import torch
from executorch.extension.llm.custom_ops import custom_ops  # noqa: F401
import executorch.kernels.quantized  # noqa: F401
from executorch.extension.llm.runner import TextLLMRunner, GenerationConfig
M = "pipeline/raw/models/"
r=TextLLMRunner(M+"Llama-3.2-1B-Instruct-SpinQuant_INT4_EO8.pte", M+"tokenizer.model")
def gen(sys_,user,n=120):
    r.reset(); out=[]
    p=(f"<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n{sys_}<|eot_id|>"
       f"<|start_header_id|>user<|end_header_id|>\n\n{user}<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n")
    r.generate(p, GenerationConfig(echo=False,max_new_tokens=n,temperature=0.0), token_callback=out.append)
    return "".join(out).replace("<|eot_id|>","").strip()
QS=[("아이랑 같이 있어요. 어떻게 해야 하나요?","I am with my child. What should I do?"),
    ("엘리베이터 타도 돼요?","Can I take the elevator?"),
    ("어디로 가야 해요?","Where should I go?"),
    ("차 타고 가도 되나요?","Can I drive there by car?"),
    ("다리를 다쳐서 잘 못 걸어요.","I hurt my leg and can barely walk."),
    ("근처에 지하철역 있는데 거기로 가도 돼요?","There is a subway station nearby. Can I go there instead?")]
# A) free-form English
SYS_EN=("You are an emergency evacuation assistant. Answer ONLY using the GROUNDING facts. Never invent shelter names, addresses or routes. "
        "If the grounding does not cover the question, say so and repeat the main instruction. Max 3 short sentences, action first.")
G_EN=("GROUNDING:\n- Hazard: earthquake. Official guidance: after shaking stops, use stairs (no elevator); go to open space such as a playground or park; keep away from buildings; do not use cars.\n"
      "- Nearest suitable site: 'Mullae Park' (outdoor open space), 420 m, about 6 min walk, head north-east.\n")
# B) sentence selection: model outputs numbers only; app renders official Korean sentences
FACTS=["흔들림이 멈추면 계단을 이용해 밖으로 나가세요. 엘리베이터는 사용하지 마세요.",
       "운동장이나 공원 같은 넓은 공간으로 대피하세요.",
       "건물·담장·간판에서 멀리 떨어져 걸으세요.",
       "차량을 이용하지 말고 걸어서 대피하세요.",
       "가장 가까운 대피장소: 문래공원, 420m, 도보 약 6분, 북동쪽.",
       "가방이나 손으로 머리를 보호하세요."]
SYS_SEL=("You select safety instructions. Output ONLY the numbers of the 1 to 3 most relevant FACTS for the question, comma separated, most important first. No other text.")
facts_txt="FACTS:\n"+"\n".join(f"{i+1}. {f}" for i,f in enumerate(["After shaking stops, use the stairs to go outside. Do not use the elevator.",
 "Evacuate to open space such as a playground or park.","Walk away from buildings, walls and signs.","Do not use cars; evacuate on foot.",
 "Nearest site: Mullae Park, 420 m, about 6 min walk, north-east.","Protect your head with a bag or your hands."]))
for ko,en in QS:
    a=gen(SYS_EN,G_EN+"\nQUESTION: "+en)
    t=time.time(); sel=gen(SYS_SEL,facts_txt+"\n\nQUESTION: "+ko,n=12); dt=time.time()-t
    ids=[int(x)-1 for x in re.findall(r"\d+",sel) if 0<int(x)<=len(FACTS)][:3]
    print(f"Q: {ko} / {en}\n  [A free EN] {a}\n  [B select] raw='{sel}' ({dt:.2f}s) -> " + " ".join(FACTS[i] for i in ids)+"\n", flush=True)
