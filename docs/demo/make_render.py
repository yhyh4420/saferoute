"""Build the demo scenes and patch the standalone page into a frame-accurate 1920x1080 renderer with burned captions.
Usage: python3 docs/demo/make_render.py  → docs/demo/build/render.html"""
import pathlib, subprocess
here = pathlib.Path(__file__).resolve().parent
# The film builder takes one scenes file: insert the Seoul geometry right after its @film header.
src = (here / "saferoute-demo.scenes.js").read_text()
head_end = src.index("*/") + 2
(here / "build").mkdir(exist_ok=True)
(here / "build/saferoute-demo.scenes.js").write_text(src[:head_end] + "\n" + (here / "seoul_geo.js").read_text() + src[head_end:])
subprocess.run(["python3", str(pathlib.Path.home() / ".claude/skills/learning-film/scripts/build.py"),
                str(here / "build/saferoute-demo.scenes.js"), "--out-dir", str(here / "build")], check=True)
s = (here / "build/saferoute-demo-standalone.html").read_text()
s = s.replace("dpr=Math.min(2,window.devicePixelRatio||1);", "dpr=window.__forceDpr||Math.min(2,window.devicePixelRatio||1);", 1)
hook = "window.__film={scenes,offs,get total(){return total;}};"
assert hook in s
s = s.replace(hook, hook + (here / "render_hooks.js").read_text(), 1)
(here / "build/render.html").write_text(s)
print("render.html ready")
