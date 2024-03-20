## PUT

```
i111433450:wrk2-arm trofik00777$ ./wrk -c 64 -t 4 -s /Users/trofik00777/Documents/itmo_s6/highload/2024-highload-dht/src/main/java/ru/vk/itmo/test/trofimovmaxim/lua/stage1/check_put.lua -d 1m -R 20000 "http://localhost:8080" -L 
Running 1m test @ http://localhost:8080
  4 threads and 64 connections
  Thread calibration: mean lat.: 1357.834ms, rate sampling interval: 3340ms
  Thread calibration: mean lat.: 1358.070ms, rate sampling interval: 3340ms
  Thread calibration: mean lat.: 1359.091ms, rate sampling interval: 3342ms
  Thread calibration: mean lat.: 1358.201ms, rate sampling interval: 3340ms
  Thread Stats   Avg      Stdev     Max   +/- Stdev
    Latency     3.94s     1.36s    6.52s    58.15%
    Req/Sec     4.53k    53.74     4.61k    64.29%
  Latency Distribution (HdrHistogram - Recorded Latency)
 50.000%    3.87s 
 75.000%    5.08s 
 90.000%    5.88s 
 99.000%    6.41s 
 99.900%    6.49s 
 99.990%    6.52s 
 99.999%    6.52s 
100.000%    6.52s 
  1069620 requests in 1.00m, 68.34MB read
Requests/sec:  17826.98
Transfer/sec:      1.14MB
```

Можем заметить что при `rps>20k` сильно вырос latency. Будем проводить профилирование на `17k rps`

```
i111433450:wrk2-arm trofik00777$ ./wrk -c 64 -t 4 -s /Users/trofik00777/Documents/itmo_s6/highload/2024-highload-dht/src/main/java/ru/vk/itmo/test/trofimovmaxim/lua/stage1/check_put.lua -d 1m -R 17000 "http://localhost:8080" -L 
Running 1m test @ http://localhost:8080
  4 threads and 64 connections
  Thread calibration: mean lat.: 460.560ms, rate sampling interval: 1502ms
  Thread calibration: mean lat.: 460.824ms, rate sampling interval: 1503ms
  Thread calibration: mean lat.: 459.888ms, rate sampling interval: 1500ms
  Thread calibration: mean lat.: 459.038ms, rate sampling interval: 1499ms
  Thread Stats   Avg      Stdev     Max   +/- Stdev
    Latency     4.10ms    4.74ms  83.52ms   91.17%
    Req/Sec     4.25k    24.01     4.38k    95.45%
  Latency Distribution (HdrHistogram - Recorded Latency)
 50.000%    2.56ms
 75.000%    5.05ms
 90.000%    8.39ms
 99.000%   23.61ms
 99.900%   54.69ms
 99.990%   78.27ms
 99.999%   81.60ms
100.000%   83.58ms
  1019311 requests in 1.00m, 65.13MB read
Requests/sec:  16988.49
Transfer/sec:      1.09MB
```

[cpu](prof_put_cpu_st3.html)

Видим значительные изменения в процентном соотношении относительно прошлых этапов.
Если раньше большинство времени занимал read/write request/response, то теперь 55% занимает ThreadPoolExecutor (в который входит ожидание таски из очереди и сама обработка запроса + возможный поход на другой кластер)

[alloc](prof_put_alloc_st3.html)

Из интересного отночительно предыдущих этапов - теперь половина всех аллокаций происходит в методе send у HttpClient

[lock](prof_put_lock_st3.html)

Значимую часть блокировок занимает все так же работа с очередями, но и метод send у HttpClient занимает 45%, что является огромной частью

## GET

Заполнил базу 600к значениями

```
i111433450:wrk2-arm trofik00777$ ./wrk -c 64 -t 4 -s /Users/trofik00777/Documents/itmo_s6/highload/2024-highload-dht/src/main/java/ru/vk/itmo/test/trofimovmaxim/lua/stage1/check_get.lua -d 1m -R 3000 "http://localhost:8080" -L 
Running 1m test @ http://localhost:8080
  4 threads and 64 connections
  Thread calibration: mean lat.: 1194.627ms, rate sampling interval: 4378ms
  Thread calibration: mean lat.: 1197.628ms, rate sampling interval: 4386ms
  Thread calibration: mean lat.: 1195.350ms, rate sampling interval: 4378ms
  Thread calibration: mean lat.: 1194.561ms, rate sampling interval: 4378ms
  Thread Stats   Avg      Stdev     Max   +/- Stdev
    Latency     9.68s     4.11s   16.52s    55.84%
    Req/Sec   538.20     16.50   574.00     72.73%
  Latency Distribution (HdrHistogram - Recorded Latency)
 50.000%    9.58s 
 75.000%   13.62s 
 90.000%   15.22s 
 99.000%   16.36s 
 99.900%   16.48s 
 99.990%   16.51s 
 99.999%   16.52s 
100.000%   16.52s 
  229518 requests in 1.00m, 24.07MB read
  Non-2xx or 3xx responses: 83
Requests/sec:   2174.37
Transfer/sec:    410.68KB
```

Видим так же огромный latency, будем профилировать на 2к rps

```
i111433450:wrk2-arm trofik00777$ ./wrk -c 64 -t 4 -s /Users/trofik00777/Documents/itmo_s6/highload/2024-highload-dht/src/main/java/ru/vk/itmo/test/trofimovmaxim/lua/stage1/check_get.lua -d 1m -R 2000 "http://localhost:8080" -L 
Running 1m test @ http://localhost:8080
  4 threads and 64 connections
  Thread calibration: mean lat.: 27.450ms, rate sampling interval: 137ms
  Thread calibration: mean lat.: 27.860ms, rate sampling interval: 139ms
  Thread calibration: mean lat.: 27.570ms, rate sampling interval: 138ms
  Thread calibration: mean lat.: 27.650ms, rate sampling interval: 137ms
  Thread Stats   Avg      Stdev     Max   +/- Stdev
    Latency    29.07ms   29.66ms 210.56ms   84.73%
    Req/Sec   501.30    102.28   839.00     71.70%
  Latency Distribution (HdrHistogram - Recorded Latency)
 50.000%   20.99ms
 75.000%   40.83ms
 90.000%   72.89ms
 99.000%  127.30ms
 99.900%  165.63ms
 99.990%  195.84ms
 99.999%  205.44ms
100.000%  210.69ms
  209943 requests in 1.00m, 22.17MB read
  Non-2xx or 3xx responses: 69
Requests/sec:   1998.32
Transfer/sec:    378.17KB
```

[cpu](prof_get_cpu_st3.html)

Видим, сто почти все время занимают системные функции связанные с потоками (по моему мнению это очень странно)

[alloc](prof_get_alloc_st3.html)

Из интересного отночительно предыдущих этапов - теперь половина всех аллокаций происходит в методе send у HttpClient (все так же как и в put)

[lock](prof_get_lock_st3.html)

Значимую часть блокировок занимает все так же работа с очередями, но и метод send у HttpClient занимает 50%, что является огромной частью (тут тоже пропорции примерно как в put запросах)

## вывод

Теперь мы не ходим локально в базу данных на все запросы, а ходим в 66% запросов в другие кластера.
Походы в другие кластера это намного более дорогостоющая операция, нежели сходить в базу локальную.
Из этого получаем значительное ухудшение в показателях сервера относительно других этапов.
Мое предположение, что дальше получится вернуть способность держать еще бОльшие нагрузки путем добавления асинхронности в походы в другие кластера
