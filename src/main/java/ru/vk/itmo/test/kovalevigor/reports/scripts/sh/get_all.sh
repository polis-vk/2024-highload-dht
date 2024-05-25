#!/bin/bash
NAME=$(date +"%s")
JFR="../../info-$NAME.jfr"
PREFIX="../../html/stage6"
(curl --location --request GET 'localhost:8080/v0/entities?start=k0' > /dev/null) &
./ap/bin/asprof -t -e cpu,alloc,lock -d $1 -f $JFR MainServer

wait
java -cp ./ap/lib/converter.jar jfr2flame --threads $JFR > "${PREFIX}/${NAME}_cpu.html"
java -cp ./ap/lib/converter.jar jfr2flame --threads --alloc $JFR > "${PREFIX}/${NAME}_alloc.html"
java -cp ./ap/lib/converter.jar jfr2flame --threads --lock $JFR > "${PREFIX}/${NAME}_lock.html"
rm $JFR
