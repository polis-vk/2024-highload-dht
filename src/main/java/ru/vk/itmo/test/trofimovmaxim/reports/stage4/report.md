## PUT

stage-3:
```
i111433450:wrk2-arm trofik00777$ ./wrk -c 64 -t 4 -s /Users/trofik00777/Documents/itmo_s6/highload/2024-highload-dht/src/main/java/ru/vk/itmo/test/trofimovmaxim/lua/stage1/check_put.lua -d 1m -R 45000 "http://localhost:8080" -L 
Running 1m test @ http://localhost:8080
  4 threads and 64 connections
  Thread calibration: mean lat.: 190.578ms, rate sampling interval: 1162ms
  Thread calibration: mean lat.: 189.605ms, rate sampling interval: 1153ms
  Thread calibration: mean lat.: 191.882ms, rate sampling interval: 1164ms
  Thread calibration: mean lat.: 191.270ms, rate sampling interval: 1164ms
  Thread Stats   Avg      Stdev     Max   +/- Stdev
    Latency    12.80ms   30.39ms 273.15ms   94.86%
    Req/Sec    11.25k   392.65    13.04k    92.90%
  Latency Distribution (HdrHistogram - Recorded Latency)
 50.000%    4.28ms
 75.000%   10.13ms
 90.000%   20.75ms
 99.000%  178.30ms
 99.900%  235.39ms
 99.990%  262.65ms
 99.999%  270.08ms
100.000%  273.41ms
  2698108 requests in 1.00m, 158.65MB read
Requests/sec:  44968.73
Transfer/sec:      2.64MB
```

stage-4:
```
i111433450:wrk2-arm trofik00777$ ./wrk -c 64 -t 4 -s /Users/trofik00777/Documents/itmo_s6/highload/2024-highload-dht/src/main/java/ru/vk/itmo/test/trofimovmaxim/lua/stage1/check_put.lua -d 1m -R 45000 "http://localhost:8080" -L 
Running 1m test @ http://localhost:8080
  4 threads and 64 connections
  Thread calibration: mean lat.: 3620.205ms, rate sampling interval: 10838ms
  Thread calibration: mean lat.: 3619.999ms, rate sampling interval: 10829ms
  Thread calibration: mean lat.: 3620.033ms, rate sampling interval: 10829ms
  Thread calibration: mean lat.: 3620.291ms, rate sampling interval: 10838ms
  Thread Stats   Avg      Stdev     Max   +/- Stdev
    Latency    18.25s     7.05s   30.44s    58.33%
    Req/Sec     5.71k    97.72     5.83k    50.00%
  Latency Distribution (HdrHistogram - Recorded Latency)
 50.000%   18.24s 
 75.000%   24.40s 
 90.000%   28.03s 
 99.000%   30.16s 
 99.900%   30.39s 
 99.990%   30.44s 
 99.999%   30.46s 
100.000%   30.46s 
  1329957 requests in 1.00m, 74.83MB read
Requests/sec:  22165.93
Transfer/sec:      1.25MB
```

Видим, что в 4 этапе заметно хуже стали работать на одном и том же rps

Будем профилировать на 20k rps:

```
i111433450:wrk2-arm trofik00777$ ./wrk -c 64 -t 4 -s /Users/trofik00777/Documents/itmo_s6/highload/2024-highload-dht/src/main/java/ru/vk/itmo/test/trofimovmaxim/lua/stage1/check_put.lua -d 1m -R 20000 "http://localhost:8080" -L 
Running 1m test @ http://localhost:8080
  4 threads and 64 connections
  Thread calibration: mean lat.: 182.065ms, rate sampling interval: 1571ms
  Thread calibration: mean lat.: 182.033ms, rate sampling interval: 1572ms
  Thread calibration: mean lat.: 182.261ms, rate sampling interval: 1572ms
  Thread calibration: mean lat.: 182.501ms, rate sampling interval: 1572ms
  Thread Stats   Avg      Stdev     Max   +/- Stdev
    Latency    11.07ms   18.66ms 124.22ms   91.70%
    Req/Sec     5.00k    77.29     5.20k    78.23%
  Latency Distribution (HdrHistogram - Recorded Latency)
 50.000%    4.58ms
 75.000%   10.56ms
 90.000%   24.56ms
 99.000%  102.53ms
 99.900%  120.38ms
 99.990%  122.88ms
 99.999%  123.71ms
100.000%  124.29ms
  1198864 requests in 1.00m, 67.46MB read
Requests/sec:  19981.07
Transfer/sec:      1.12MB
```

[cpu](prof_put_cpu_st4.html)

[alloc](prof_put_alloc_st4.html)

[lock](prof_put_lock_st4.html)

По сравнению с прошлым этапом сильных различий не наблюдаем

## GET

Заполнил базу 600к значениями


stage-3:
```
i111433450:wrk2-arm trofik00777$ ./wrk -c 64 -t 4 -s /Users/trofik00777/Documents/itmo_s6/highload/2024-highload-dht/src/main/java/ru/vk/itmo/test/trofimovmaxim/lua/stage1/check_get.lua -d 1m -R 50000 "http://localhost:8080" -L 
Running 1m test @ http://localhost:8080
  4 threads and 64 connections
  Thread calibration: mean lat.: 10.099ms, rate sampling interval: 52ms
  Thread calibration: mean lat.: 10.090ms, rate sampling interval: 48ms
  Thread calibration: mean lat.: 10.125ms, rate sampling interval: 51ms
  Thread calibration: mean lat.: 10.102ms, rate sampling interval: 53ms
  Thread Stats   Avg      Stdev     Max   +/- Stdev
    Latency     8.59ms   13.34ms 140.67ms   92.14%
    Req/Sec    12.63k     1.52k   16.14k    69.85%
  Latency Distribution (HdrHistogram - Recorded Latency)
 50.000%    4.41ms
 75.000%    9.96ms
 90.000%   18.91ms
 99.000%   75.07ms
 99.900%  119.81ms
 99.990%  129.41ms
 99.999%  135.68ms
100.000%  140.80ms
  2998065 requests in 1.00m, 557.63MB read
  Non-2xx or 3xx responses: 2241
Requests/sec:  49967.73
Transfer/sec:      9.29MB
```

stage-4:
```
i111433450:wrk2-arm trofik00777$ ./wrk -c 64 -t 4 -s /Users/trofik00777/Documents/itmo_s6/highload/2024-highload-dht/src/main/java/ru/vk/itmo/test/trofimovmaxim/lua/stage1/check_get.lua -d 1m -R 50000 "http://localhost:8080" -L 
Running 1m test @ http://localhost:8080
  4 threads and 64 connections
  Thread calibration: mean lat.: 3312.623ms, rate sampling interval: 11354ms
  Thread calibration: mean lat.: 3312.387ms, rate sampling interval: 11354ms
  Thread calibration: mean lat.: 3312.572ms, rate sampling interval: 11354ms
  Thread calibration: mean lat.: 3311.771ms, rate sampling interval: 11354ms
  Thread Stats   Avg      Stdev     Max   +/- Stdev
    Latency    21.30s     8.69s   36.27s    57.75%
    Req/Sec     4.98k    41.11     5.01k    75.00%
  Latency Distribution (HdrHistogram - Recorded Latency)
 50.000%   21.35s 
 75.000%   28.79s 
 90.000%   33.34s 
 99.000%   35.98s 
 99.900%   36.24s 
 99.990%   36.27s 
 99.999%   36.31s 
100.000%   36.31s 
  1185594 requests in 1.00m, 217.56MB read
  Non-2xx or 3xx responses: 5859
Requests/sec:  19760.02
Transfer/sec:      3.63MB
```

Видим, что в 4 этапе заметно хуже стали работать на одном и том же rps

Будем профилировать на 20k rps:

```
i111433450:wrk2-arm trofik00777$ ./wrk -c 64 -t 4 -s /Users/trofik00777/Documents/itmo_s6/highload/2024-highload-dht/src/main/java/ru/vk/itmo/test/trofimovmaxim/lua/stage1/check_get.lua -d 1m -R 20000 "http://localhost:8080" -L 
Running 1m test @ http://localhost:8080
  4 threads and 64 connections
  Thread calibration: mean lat.: 35.410ms, rate sampling interval: 137ms
  Thread calibration: mean lat.: 35.240ms, rate sampling interval: 136ms
  Thread calibration: mean lat.: 35.464ms, rate sampling interval: 137ms
  Thread calibration: mean lat.: 35.483ms, rate sampling interval: 137ms
  Thread Stats   Avg      Stdev     Max   +/- Stdev
    Latency    66.98ms   70.67ms 215.81ms   75.02%
    Req/Sec     5.01k   284.23     5.30k    90.06%
  Latency Distribution (HdrHistogram - Recorded Latency)
 50.000%   28.32ms
 75.000%  137.60ms
 90.000%  189.57ms
 99.000%  206.34ms
 99.900%  211.33ms
 99.990%  213.63ms
 99.999%  215.17ms
100.000%  215.94ms
  1198437 requests in 1.00m, 219.93MB read
  Non-2xx or 3xx responses: 6257
Requests/sec:  19974.01
Transfer/sec:      3.67MB
```

[cpu](prof_get_cpu_st4.html)

[alloc](prof_get_alloc_st4.html)

[lock](prof_get_lock_st4.html)

Также видим, что сильных изменений по сравнению с предыдущим этапом нет

## вывод

В целом сервер стал работать медленнее и держать меньшее rps.
Оно в целом логично и ожидаемо, тк в нагрузочном тестировании мы задаем from=3,ack=2, соответственно ходим вместо 1 места аж в 3.
Получается нагрузка на сеть растет кратно, от этого и дольше обработка, тк мы ждем все 3 похода, ведь нет асинхронности (будет только в следующем этапе).
Собственно добавление асинхронности в этом месте должно сильно ускорить наш сервер.
По профилированию мало что изменилось (процентное соотношение блоков программы), ведь из изменений было лишь добавление нескольких походов вместо одного
