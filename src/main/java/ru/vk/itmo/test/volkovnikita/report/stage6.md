# Нагрузочное тестирование с wrk

## Stage 6

Сейчас БД заполнена ~ на 1 гб, добавим ещё записей:

wrk -d 120 -c 64 -t 4 -R 11000 -L -s /home/ravenhub/IdeaProjects/2024-highload-dht/src/main/java/ru/vk/itmo/test/volkovnikita/lua/stage1/put.lua http://127.0.0.1:8080
Running 2m test @ http://127.0.0.1:8080
4 threads and 64 connections

Теперь получим диапозон запросов с помощью curl и снимем профили cpu, alloc, lock
начиная с 1-ой

curl -v http://localhost:8080/v0/entities\?start\=1

## cpu
[profile-range-cpu-get.html](..%2Fdata%2Fstage6%2Fprofile-range-cpu-get.html)

## alloc
[profile-range-alloc-get.html](..%2Fdata%2Fstage6%2Fprofile-range-alloc-get.html)

## lock
[profile-range-lock-get.html](..%2Fdata%2Fstage6%2Fprofile-range-lock-get.html)