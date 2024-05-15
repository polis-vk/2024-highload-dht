## PUT

```
i111433450:wrk2-arm trofik00777$ ./wrk -c 64 -t 4 -s /Users/trofik00777/Documents/itmo_s6/highload/2024-highload-dht/src/main/java/ru/vk/itmo/test/trofimovmaxim/lua/stage1/check_put.lua -d 1m -R 30000 "http://localhost:8080" -L 
Running 1m test @ http://localhost:8080
  4 threads and 64 connections
  Thread calibration: mean lat.: 992.135ms, rate sampling interval: 3676ms
  Thread calibration: mean lat.: 995.437ms, rate sampling interval: 3700ms
  Thread calibration: mean lat.: 1001.864ms, rate sampling interval: 3713ms
  Thread calibration: mean lat.: 988.766ms, rate sampling interval: 3661ms
  Thread Stats   Avg      Stdev     Max   +/- Stdev
    Latency     7.27s     3.06s   12.62s    56.31%
    Req/Sec     3.99k   173.20     4.29k    63.46%
  Latency Distribution (HdrHistogram - Recorded Latency)
 50.000%    7.67s 
 75.000%    9.91s 
 90.000%   11.29s 
 99.000%   12.13s 
 99.900%   12.42s 
 99.990%   12.53s 
 99.999%   12.62s 
100.000%   12.62s 
Requests/sec:  25921.37
Transfer/sec:      0.90MB
```

Будем профилировать на 25k rps:

```
i111433450:wrk2-arm trofik00777$ ./wrk -c 64 -t 4 -s /Users/trofik00777/Documents/itmo_s6/highload/2024-highload-dht/src/main/java/ru/vk/itmo/test/trofimovmaxim/lua/stage1/check_put.lua -d 1m -R 25000 "http://localhost:8080" -L 
Running 1m test @ http://localhost:8080
  4 threads and 64 connections
  Thread calibration: mean lat.: 32.138ms, rate sampling interval: 164ms
  Thread calibration: mean lat.: 30.932ms, rate sampling interval: 157ms
  Thread calibration: mean lat.: 32.172ms, rate sampling interval: 164ms
  Thread calibration: mean lat.: 29.839ms, rate sampling interval: 149ms
  Thread Stats   Avg      Stdev     Max   +/- Stdev
    Latency    40.33ms   44.02ms 241.02ms   84.16%
    Req/Sec     3.75k   548.98     7.27k    73.87%
  Latency Distribution (HdrHistogram - Recorded Latency)
 50.000%   23.92ms
 75.000%   62.21ms
 90.000%  105.66ms
 99.000%  177.28ms
 99.900%  206.59ms
 99.990%  225.28ms
 99.999%  237.69ms
100.000%  241.15ms
Requests/sec:  24960.85
Transfer/sec:    862.00KB
```

[cpu](prof_put_cpu_st5.html)

[alloc](prof_put_alloc_st5.html)

[lock](prof_put_lock_st5.html)

По сравнению с прошлым этапом видим изменения в lock, конкретно наши функции invoke теперь меньше локают, тк перешли на асинхронное общение

## GET

Заполнил базу 600к значениями

```
i111433450:wrk2-arm trofik00777$ ./wrk -c 64 -t 4 -s /Users/trofik00777/Documents/itmo_s6/highload/2024-highload-dht/src/main/java/ru/vk/itmo/test/trofimovmaxim/lua/stage1/check_get.lua -d 1m -R 30000 "http://localhost:8080" -L 
Running 1m test @ http://localhost:8080
  4 threads and 64 connections
  Thread calibration: mean lat.: 260.369ms, rate sampling interval: 1033ms
  Thread calibration: mean lat.: 247.281ms, rate sampling interval: 1000ms
  Thread calibration: mean lat.: 246.821ms, rate sampling interval: 990ms
  Thread calibration: mean lat.: 266.203ms, rate sampling interval: 1040ms
  Thread Stats   Avg      Stdev     Max   +/- Stdev
    Latency     3.05s     1.52s    6.13s    55.22%
    Req/Sec     4.50k   281.23     5.13k    62.89%
  Latency Distribution (HdrHistogram - Recorded Latency)
 50.000%    3.01s 
 75.000%    4.39s 
 90.000%    5.27s 
 99.000%    5.71s 
 99.900%    5.93s 
 99.990%    6.11s 
 99.999%    6.13s 
100.000%    6.13s 
Requests/sec:  25107.58
Transfer/sec:      3.32MB
```

Будем профилировать на 25k rps:

```
i111433450:wrk2-arm trofik00777$ ./wrk -c 64 -t 4 -s /Users/trofik00777/Documents/itmo_s6/highload/2024-highload-dht/src/main/java/ru/vk/itmo/test/trofimovmaxim/lua/stage1/check_get.lua -d 1m -R 25000 "http://localhost:8080" -L 
Running 1m test @ http://localhost:8080
  4 threads and 64 connections
  Thread calibration: mean lat.: 1.707ms, rate sampling interval: 10ms
  Thread calibration: mean lat.: 1.697ms, rate sampling interval: 10ms
  Thread calibration: mean lat.: 1.700ms, rate sampling interval: 10ms
  Thread calibration: mean lat.: 1.687ms, rate sampling interval: 10ms
  Thread Stats   Avg      Stdev     Max   +/- Stdev
    Latency     1.79ms    1.13ms  18.46ms   85.87%
    Req/Sec     3.95k   485.91     7.40k    79.16%
  Latency Distribution (HdrHistogram - Recorded Latency)
 50.000%    1.55ms
 75.000%    2.15ms
 90.000%    2.86ms
 99.000%    6.59ms
 99.900%   10.36ms
 99.990%   13.35ms
 99.999%   15.99ms
100.000%   18.48ms
Requests/sec:  24990.54
Transfer/sec:      2.75MB
```

[cpu](prof_get_cpu_st5.html)

[alloc](prof_get_alloc_st5.html)

[lock](prof_get_lock_st5.html)

Также видим, что по сравнению с прошлым этапом изменился lock, конкретно наши функции invoke теперь меньше локают, тк перешли на асинхронное общение

## вывод

В целом сервер стал работать получше и держать немного больше rps по сравнению с 4 этапом.
Но все также хуже, чем в 3 этапе.
Оно в целом ожидаемо, тк в нагрузочном тестировании мы задаем from=3,ack=2, соответственно ходим вместо 1 места аж в 3 (нагрузка на сеть растет кратно, от этого и дольше обработка, тк мы ждем несколько походов).
Собственно лучше 4 этапа, тк асинхронность добавилась при общении.
