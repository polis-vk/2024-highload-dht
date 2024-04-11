#!/bin/bash
NAME=$(date +"%s")
JFR="../../info-$NAME.jfr"
PREFIX="../../html/stage4"
(wrk2 -c 128 -t 8 -L -d $1 -R $2 -s "../lua/$3.lua" http://localhost:8080 > "${PREFIX}/${NAME}_wrk.txt") &
./ap/bin/asprof -t -e cpu,alloc,lock -d $1 -f $JFR MainServer

wait
java -cp ./ap/lib/converter.jar jfr2flame --threads $JFR > "${PREFIX}/${NAME}_cpu.html"
java -cp ./ap/lib/converter.jar jfr2flame --threads --alloc $JFR > "${PREFIX}/${NAME}_alloc.html"
java -cp ./ap/lib/converter.jar jfr2flame --threads --lock $JFR > "${PREFIX}/${NAME}_lock.html"
rm $JFR
