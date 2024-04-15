# Нагрузочное тестирование с wrk

## Stage 3

По итогу я решил отказаться от consistent hashing в угоду murmur3, 
это позволило повысить производительность и сделать распределение равномерным.
Так же все запуски производились при одном треде и одном конекшене, сделано это было 
для будущего сравнительного анализа улучшения или ухудшения системы.

## PUT 

RPS  AVG Latency
1300  26.74 ms
1400  54.25 ms
1500  96.33 ms
1600  250.35 ms

ПРИ 1500 RPS я запускал так же на 60, 120, 240 и 300 s, сервис выдерживает нагрузку

ravenhub@ravenhub-Virtual-Machine:~$ wrk -d 10 -c 1 -t 1 -R 1500 -L -s 
/home/ravenhub/IdeaProjects/2024-highload-dht/src/main/java/ru/vk/itmo/test/volkovnikita/lua/stage1/put.lua http://127.0.0.1:8080
Running 10s test @ http://127.0.0.1:8080
1 threads and 1 connections
Thread Stats   Avg      Stdev     Max   +/- Stdev
Latency    96.33ms   89.43ms 283.39ms   69.11%
Req/Sec       -nan      -nan   0.00      0.00%
Latency Distribution (HdrHistogram - Recorded Latency)
50.000%   68.80ms
75.000%  168.57ms
90.000%  231.55ms
99.000%  277.25ms
99.900%  282.11ms
99.990%  283.39ms
99.999%  283.65ms
100.000%  283.65ms


[profile-1500-cpu-put.html](..%2Fdata%2Fstage3%2Fput%2Fprofile-1500-cpu-put.html)

Хэширование почти ничего не стоит, всего 10 сэмплов, но, гораздо больше уходит на работу ThreadPoolExecutor 
6.13% сэмплов на getTask c одного httpClient и редирект на другую ноду 4.46%

[profile-1500-alloc-put.html](..%2Fdata%2Fstage3%2Fput%2Fprofile-1500-alloc-put.html)

Больше всего уходит на редирект запросов, 7.06% сэмплов только с одного треда, то есть 
примерно 57%, ещё примерно 22% сэмплов уходит на AsyncReceiver

[profile-1500-lock-put.html](..%2Fdata%2Fstage3%2Fput%2Fprofile-1500-lock-put.html)

Больше всего блокировок приходится на HttpClient 

![Histogram_get_1330.png](..%2Fdata%2Fstage3%2Fget%2FHistogram_get_1330.png)



## GET

RPS  AVG Latency
1200  25.25 ms
1330  49.70 ms
1400  180.33 ms
1450  240.35 ms

При 1330 аналогично как при PUT 


ravenhub@ravenhub-Virtual-Machine:~$ wrk -d 10 -c 1 -t 1 -R 1330 -L -s 
/home/ravenhub/IdeaProjects/2024-highload-dht/src/main/java/ru/vk/itmo/test/volkovnikita/lua/stage1/get.lua http://127.0.0.1:8080
Running 10s test @ http://127.0.0.1:8080
1 threads and 1 connections
Thread Stats   Avg      Stdev     Max   +/- Stdev
Latency    49.70ms   45.34ms 172.93ms   64.61%
Req/Sec       -nan      -nan   0.00      0.00%
Latency Distribution (HdrHistogram - Recorded Latency)
50.000%   44.80ms
75.000%   74.18ms
90.000%  119.42ms
99.000%  168.32ms
99.900%  172.16ms
99.990%  172.80ms
99.999%  173.05ms
100.000%  173.05ms

[profile-1330-cpu-get.html](..%2Fdata%2Fstage3%2Fget%2Fprofile-1330-cpu-get.html)

[profile-1330-alloc-get.html](..%2Fdata%2Fstage3%2Fget%2Fprofile-1330-alloc-get.html)

[profile-1330-lock-get.html](..%2Fdata%2Fstage3%2Fget%2Fprofile-1330-lock-get.html)

![Histogram_put_1500.png](..%2Fdata%2Fstage3%2Fput%2FHistogram_put_1500.png)

Обобщив в случае с GET можно так же заметить что HttpClient и его методы занимает львиную долю во всех трёх
графиках

## Summary 

В сравнении со вторым этапом производительность упала примерно в 5 раза для PUT, хоть 
у меня и представлены в отчёте результаты при с 128 и t 128, для 1 треда и 1 конекта 
сервер выдерживал 8000 RPS 

Для GET примерно аналогичная ситуация, производительность упала примерно в 4 раза.

Соответственно добавление шардироварования и в свою очередь сетевых запросов существенно 
влияет на пропускную способность и нагруженность.

