#!/bin/sh

..wrk2-arm/wrk -d 30 -t 1 -c 1 -R 40000 -L http://localhost:8080/v0/entity?id=100
..async-profiler-3.0-macos/bin/asprof -d 30 -f ~/Desktop/highload/2024-highload-dht/src/main/java/ru/vk/itmo/test/alexeyshemetov/results/profile.html  Server