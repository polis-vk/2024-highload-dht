#!/bin/bash

result='/Users/alexshemetov/Desktop/highload/2024-highload-dht/src/main/java/ru/vk/itmo/test/alexeyshemetov/results'
stageA=$result'/stage1'
localhost='http://localhost:8080'

../wrk2-arm/wrk -d 30 -t 1 -c 1 -s $stageA'/wrk/get.lua' -L $localhost -R 4000 &
../async-profiler-3.0-macos/bin/asprof -e alloc -d 30 -f $stageA'/html/alloc_get4000.html' ServerRunner