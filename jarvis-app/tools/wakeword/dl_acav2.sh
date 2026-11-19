#!/bin/bash
# Range-download 30 evenly spaced slices (~100 MB each, 32768 rows x 3072 B) of the ACAV100M feature file.
U=https://huggingface.co/datasets/davidscripka/openwakeword_features/resolve/main
OUT=$1
HDR=128; ROW=3072; NROWS=5625000; NCH=${NCH:-400}; CH=${CH:-3277}
for i in $(seq 0 $((NCH-1))); do
  start_row=$(( 16000 + i * (NROWS - CH - 32000) / (NCH-1) ))
  s=$(( HDR + start_row*ROW )); e=$(( s + CH*ROW - 1 ))
  echo "$i $start_row $s $e"
done | xargs -P 4 -n 4 sh -c 'f='$OUT'/chunk_$(printf %02d $0).bin; [ -s $f ] && [ $(stat -c%s $f) -eq $(( $3-$2+1 )) ] || curl -sSL --retry 5 -r $2-$3 -o $f '$U'/openwakeword_features_ACAV100M_2000_hrs_16bit.npy; echo done $0 $1'

