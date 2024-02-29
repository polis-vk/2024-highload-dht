#!/bin/bash
NAME=$(date +"%s")
JFR="../../info-$NAME.jfr"
(wrk2 -c 1 -t 1 -L -d $1 -R $2 -s "../lua/$3.lua" http://localhost:8080 > "../../html/${NAME}_wrk.txt") &
./ap/bin/asprof -e cpu,alloc -d $1 -f $JFR MainServer

wait
java -cp ./ap/lib/converter.jar jfr2flame $JFR > "../../html/${NAME}_cpu.html"
java -cp ./ap/lib/converter.jar jfr2flame --alloc $JFR > "../../html/${NAME}_alloc.html"
rm $JFR
