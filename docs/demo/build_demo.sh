#!/bin/bash
# Composite the 3-minute demo: rendered 3b1b-style frames (make_render.py + frame capture) +
# real phone recordings in the phone frame + fal.ai narration.  Output: docs/demo/build/saferoute-demo.mp4
set -euo pipefail
cd "$(dirname "$0")"
FR=build/frames RAW=raw NAR=raw/narration OUT=build/saferoute-demo.mp4
FPS=24 TOTAL=161
# phone area in the 1920x1080 frame (PH in saferoute-demo.scenes.js)
PX=450 PY=56 PW=392 PHH=840

# scene start offsets (seconds), must match the scene durations in saferoute-demo.scenes.js
declare -a BLOCKS=(1a 1b 1c 2 3a 3b 3c 3d 3e 4a 4b 4c 5)
declare -a START=(0 7 17 30 42 57 69 82 94 109 122 132 145)
# phone clips: name  scene-start  scene-length  trim-start-in-clip
CLIPS=("3a_quake 42 15 1.3" "3b_flood 57 12 0" "3c_night 69 13 0" "3d_qa 82 12 0" "3e_plan_map 94 15 0")

args=(-y -v error -framerate $FPS -i "$FR/f%05d.jpg")
for c in "${CLIPS[@]}"; do set -- $c; args+=(-i "$RAW/$1.mp4"); done
for b in "${BLOCKS[@]}"; do args+=(-i "$NAR/$b.wav"); done

fc="[0:v]format=yuv420p[base0];"
last=base0
for k in "${!CLIPS[@]}"; do
  set -- ${CLIPS[$k]}; name=$1 st=$2 len=$3 trim=$4; i=$((k+1))
  fc+="[$i:v]trim=start=$trim,setpts=PTS-STARTPTS,fps=$FPS,scale=$PW:$PHH,tpad=stop_mode=clone:stop_duration=$len,trim=duration=$len,"
  fc+="fade=in:st=0:d=0.4,fade=out:st=$(echo "$len-0.4" | bc):d=0.4,setpts=PTS+$st/TB[p$i];"
  fc+="[$last][p$i]overlay=$PX:$PY:enable='between(t,$st,$(echo "$st+$len" | bc))':eof_action=pass[base$i];"
  last=base$i
done
na=${#CLIPS[@]}
for k in "${!BLOCKS[@]}"; do
  i=$((na+1+k)); ms=$(( (${START[$k]}*1000) + 500 ))
  fc+="[$i:a]adelay=${ms}|${ms},aformat=channel_layouts=stereo[a$k];"
done
for k in "${!BLOCKS[@]}"; do fc+="[a$k]"; done
fc+="amix=inputs=${#BLOCKS[@]}:normalize=0,apad,atrim=duration=$TOTAL,loudnorm=I=-16:TP=-1.5:LRA=11,aresample=48000[aout]"

ffmpeg "${args[@]}" -filter_complex "$fc" -map "[$last]" -map "[aout]" \
  -c:v libx264 -preset medium -crf 18 -pix_fmt yuv420p -r $FPS -c:a aac -b:a 192k -t $TOTAL -movflags +faststart "$OUT"
echo "wrote $OUT ($(ffprobe -v error -show_entries format=duration -of csv=p=0 "$OUT")s)"
