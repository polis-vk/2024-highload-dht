# Нагрузочное тестирование с wrk

## Stage 5

Все запросы были с тремя узлами и кворумом из двух узлов

## PUT

RPS  AVG Latency
10000 23.48 ms
10500  53.11 ms
11000  85.16 ms
11500  178.54 ms

ПРИ 11000 RPS запускал так же на 60, 120, 240 и 300s, сервис выдерживает нагрузку

ravenhub@ravenhub-Virtual-Machine:~$ wrk -d 160 -c 16 -t 4 -R 11000 -L -s /home/ravenhub/IdeaProjects/2024-highload-dht/src/main/java/ru/vk/itmo/test/volkovnikita/lua/stage1/put.lua http://127.0.0.1:8080
Running 160s test @ http://127.0.0.1:8080
4 threads and 16 connections
Thread Stats   Avg      Stdev     Max   +/- Stdev
Latency    85.16ms   41.33ms 198.27ms   62.25%
Req/Sec       -nan      -nan   0.00      0.00%
Latency Distribution (HdrHistogram - Recorded Latency)
50.000%   80.57ms
75.000%  115.01ms
90.000%  142.08ms
99.000%  184.96ms
99.900%  194.94ms
99.990%  196.86ms
99.999%  197.76ms
100.000%  198.40ms

[profile-11000-cpu-put.html](..%2Fdata%2Fstage5%2Fput%2Fprofile-11000-cpu-put.html)
вызов метода getTask уменьшилось по сравнению с предыдущим этапом, но стало гораздо больше 
асинхронных операций, CompletableFuture который занимает (415 samples, 12.20%)


[profile-11000-alloc-put.html](..%2Fdata%2Fstage5%2Fput%2Fprofile-11000-alloc-put.html)
С аллокациями примерно такая же ситуация, асинхронные операции класса CompletableFuture 
занимают (3641 samples, 31.43%)


[profile-11000-lock-put.html](..%2Fdata%2Fstage5%2Fput%2Fprofile-11000-lock-put.html)
Так же и с локами, класс CompletableFuture занимает 29.71% 

![Histogram_put_11000.png](..%2Fdata%2Fstage5%2Fput%2FHistogram_put_11000.png)

## GET

RPS  AVG Latency
14300  23.11 ms
14500  31.46 ms
14700  46.94 ms
15000  154.69 ms

При 14700 запускал так же на 60, 120, 240 и 300s, сервис выдерживает нагрузку

ravenhub@ravenhub-Virtual-Machine:~$ wrk -d 160 -c 16 -t 4 -R 14700 -L -s /home/ravenhub/IdeaProjects/2024-highload-dht/src/main/java/ru/vk/itmo/test/volkovnikita/lua/stage1/get.lua http://127.0.0.1:8080
Running 160s test @ http://127.0.0.1:8080
4 threads and 16 connections
Thread Stats   Avg      Stdev     Max   +/- Stdev
Latency    46.94ms   29.06ms 124.10ms   68.36%
Req/Sec       -nan      -nan   0.00      0.00%
Latency Distribution (HdrHistogram - Recorded Latency)
50.000%   38.11ms
75.000%   64.99ms
90.000%   96.19ms
99.000%  115.39ms
99.900%  121.47ms
99.990%  123.26ms
99.999%  124.10ms
100.000%  124.16ms

[profile-14700-cpu-get.html](..%2Fdata%2Fstage5%2Fget%2Fprofile-14700-cpu-get.html)
У cpu get примерно аналогичная ситуация как у cpu put, класс CompletableFuture занимает
(488 samples, 13.03%), вызов метода getTask так же уменьшился по сравнению с предыдущим этапом
и занимает (322 samples, 8.87%)

[profile-14700-alloc-get.html](..%2Fdata%2Fstage5%2Fget%2Fprofile-14700-alloc-get.html)
Так же и аллокациями, добавилось больше асинхронных операций и появился класс CompletableFuture
который занимает (4518 samples, 30.12%)

[profile-14700-lock-get.html](..%2Fdata%2Fstage5%2Fget%2Fprofile-14700-lock-get.html)
Так же появился класс CompletableFuture который занимает 29.53%

![Histogram_get_14700.png](..%2Fdata%2Fstage5%2Fget%2FHistogram_get_14700.png)

## Summary

В сравнении с предыдущим этапом, производительность как для операций get, так и для put значительно увеличилась.
Однако стоит отметить, что сложность логики приложения также возросла, что может сказаться на поддержке и расширении проекта в будущем.
Тестирование проводилось при использовании 16 соединений и 4 потоков.
Для подтверждения улучшения производительности в менее нагруженной среде — при одном соединении и одном потоке —
были выполнены дополнительные тесты. Результаты этих тестов показали значительный рост производительности: 
для операции put с 1250 до 1510, а для get с 1220 до 1600.
