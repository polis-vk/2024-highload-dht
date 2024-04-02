# Нагрузочное тестирование с wrk

## Stage 4

Все запросы были с тремя узлами и кворумом из двух узлов

## PUT

RPS  AVG Latency
1000  20.58 ms
1100  35.33 ms
1250  48.22 ms
1350  195.75 ms

ПРИ 1250 RPS запускал так же на 60, 120, 240 и 300s, сервис выдерживает нагрузку

ravenhub@ravenhub-Virtual-Machine:~$ wrk -d 300 -c 1 -t 1 -R 1250 -L -s /home/ravenhub/IdeaProjects/2024-highload-dht/src/main/java/ru/vk/itmo/test/volkovnikita/lua/stage1/put.lua http://127.0.0.1:8080
Running 300s test @ http://127.0.0.1:8080
1 threads and 1 connections
Thread calibration: mean lat.: 57.317ms, rate sampling interval: 306ms
Thread Stats   Avg      Stdev     Max   +/- Stdev
Latency    48.22ms   48.12ms 145.15ms   75.76%
Req/Sec     1.28k    78.74     1.38k    68.75%
Latency Distribution (HdrHistogram - Recorded Latency)
50.000%   29.74ms
75.000%   92.16ms
90.000%  123.90ms
99.000%  142.08ms
99.900%  145.02ms
99.990%  145.28ms
99.999%  145.28ms
100.000%  145.28ms


[profile-1250-cpu-put.html](..%2Fdata%2Fstage4%2Fput%2Fprofile-1250-cpu-put.html)

По графу видно, что ThreadPoolExecutor метода getTask уменьшилось и сейчас занимает (68 samples, 16.79%), однако
runWorker занимает (289 samples, 71.36%) и так же process request(110 samples, 27.16%)

[profile-1250-alloc-put.html](..%2Fdata%2Fstage4%2Fput%2Fprofile-1250-alloc-put.html)

Из графа видно, что метод processRequest занимает большую часть, а именно (730 samples, 60.99%)
дополнительная нагрузка появилась из-за построения запросов сразу к нескольким узлам кластера

[profile-1250-lock-put.html](..%2Fdata%2Fstage4%2Fput%2Fprofile-1250-lock-put.html)

Основное время ожидания блокировок приходится на HttpClient(SelectorManager, SelectorImpl.select)

![Histogram_put_1250.png](..%2Fdata%2Fstage4%2Fput%2FHistogram_put_1250.png)

## GET

RPS  AVG Latency
1200  26.01 ms
1220  31.92 ms
1250  50.31 ms
1300  130.69 ms

При 1220 запускал так же на 60, 120, 240 и 300s, сервис выдерживает нагрузку

ravenhub@ravenhub-Virtual-Machine:~$ wrk -d 300 -c 1 -t 1 -R 1220 -L -s /home/ravenhub/IdeaProjects/2024-highload-dht/src/main/java/ru/vk/itmo/test/volkovnikita/lua/stage1/get.lua http://127.0.0.1:8080
Running 300s test @ http://127.0.0.1:8080
1 threads and 1 connections
Thread calibration: mean lat.: 10.781ms, rate sampling interval: 57ms
Thread Stats   Avg      Stdev     Max   +/- Stdev
Latency    31.92ms   31.92ms 120.19ms   80.32%
Req/Sec     1.23k   175.64     1.47k    76.00%
Latency Distribution (HdrHistogram - Recorded Latency)
50.000%   19.81ms
75.000%   51.01ms
90.000%   84.16ms
99.000%  113.02ms
99.900%  118.65ms
99.990%  119.74ms
99.999%  120.25ms
100.000%  120.25ms

[profile-1220-cpu-get.html](..%2Fdata%2Fstage4%2Fget%2Fprofile-1220-cpu-get.html)

Из графа видно, что processRequest теперь занимает больше ресурсов, а именно (139 samples, 34.84%)
так же метод collectResponses (108 samples, 27.07%), а ресурсы на ThreadPoolExecutor метода getTask
так же уменьшились и сейчас (56 samples, 14.04%)

[profile-1220-alloc-get.html](..%2Fdata%2Fstage4%2Fget%2Fprofile-1220-alloc-get.html)

Так же видно, что метож processRequest занимает больше ресурсов (736 samples, 63.28%) и 
SequentialScheduler$ShedulablleTask.run (208 samples, 17.88%)


[profile-1220-lock-get.html](..%2Fdata%2Fstage4%2Fget%2Fprofile-1220-lock-get.html)

Основное время ожидания блокировок приходится на HttpClient(SelectorManager, SelectorImpl.select)

![Histogram_get_1220.png](..%2Fdata%2Fstage4%2Fget%2FHistogram_get_1220.png)

## Summary

В сравнении со третьем этапом производительность так же упала и для get, и для put, 
связанно это с репликацией, однако это даёт нам более надёжную структуру хранения данных и так же 
теперь мы можем указывать количество узлов для выполнения запроса
