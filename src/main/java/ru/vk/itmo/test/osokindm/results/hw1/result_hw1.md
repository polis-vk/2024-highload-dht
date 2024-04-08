## Нахождение точки разладки
### GET
Размер БД = 2.1gb

при выполнении 10'000 запросов в секунду на протяжении 30 секунд получили среднее 
время выполнения запроса равное 22.64ms, максимальное - 182.66ms, что уже сопоставимо с одной секундой 
и выше допустимого порядка.
Количество запросов было уменьшено на 20%. Получили следующие результаты: \
`wrk -d 30 -t 1 -c 1 -R 8000 -L  -s ./src/main/java/ru/vk/itmo/test/osokindm/wrk_scripts/get.lua http://localhost:8080/v0/entity`


```
Running 30s test @ http://localhost:8080/v0/entity
  1 threads and 1 connections
  Thread calibration: mean lat.: 1.055ms, rate sampling interval: 10ms
  Thread Stats   Avg      Stdev     Max   +/- Stdev
    Latency     1.36ms    2.31ms  22.88ms   94.51%
    Req/Sec     8.44k   770.72    14.44k    73.79%
  Latency Distribution (HdrHistogram - Recorded Latency)
 50.000%    0.85ms
 75.000%    1.20ms
 90.000%    2.22ms
 99.000%   15.76ms
 99.900%   21.26ms
 99.990%   22.77ms
 99.999%   22.85ms
100.000%   22.90ms
```

Необходимо убедиться, что нагрузка является стабильной, для этого проведем повторное тестирование, но длительностью
5 минут:

` wrk -d 300 -t 1 -c 1 -R 8000 -L  -s ./src/main/java/ru/vk/itmo/test/osokindm/wrk_scripts/get.lua http://localhost:8080/v0/entity`

``` 
Running 5m test @ http://localhost:8080/v0/entity
  1 threads and 1 connections
  Thread calibration: mean lat.: 1.194ms, rate sampling interval: 10ms
  Thread Stats   Avg      Stdev     Max   +/- Stdev
    Latency     1.43ms    2.49ms  39.58ms   96.02%
    Req/Sec     8.44k     0.88k   19.67k    72.04%
  Latency Distribution (HdrHistogram - Recorded Latency)
 50.000%    0.94ms
 75.000%    1.52ms
 90.000%    2.37ms
 99.000%   13.39ms
 99.900%   33.85ms
 99.990%   38.72ms
 99.999%   39.39ms
100.000%   39.62ms
----------------------------------------------------------
  2399973 requests in 5.00m, 148.53MB read
Requests/sec:   7999.91
Transfer/sec:    506.97KB

```
Можно считать, что нагрузка является допустимой и проведена ниже точки разладки. 
Низкое Latency при столь высоком rps достигается за счет того, что все Page object свободно вмещаеются в оперативной памяти и, как следствие, у нас нет медленных запросов к диску

[Flamegraph CPU](profiler/profile_get_8000.svg)
Большую часть процессорного времени (>43%) занимает работа с MemorySegment, бинарный поиск и метод сравнения сегментов MemorySegmentComparator.compare
20% процессорного времени занимало выполенине кода ядра, в честности 19% ушло на syscall получения набора семафоров (SEMGET).

[Flamegraph Alloc](profiler/profile_get_alloc_8000.svg)

Больше всего выделений памяти (8%) уходило на Mapped MemorySegment

На данный момент все запросы выполняются внутри SelectorThread, чего быть по идее не должно, поэтому возможна оптимизация путем делегирования задачи WorkerPool'у


Если сделать GET запрос не по рандомному индексу, а по одному и тому же, то средняя задержка выполнения запроса снизилась 
на 30%, что связано с кешированием результата:
``` Thread calibration: mean lat.: 1.071ms, rate sampling interval: 10ms
Thread Stats   Avg      Stdev     Max   +/- Stdev
Latency     1.00ms  650.63us   5.64ms   67.41%
Req/Sec     8.45k   765.16    12.56k    71.79%
Latency Distribution (HdrHistogram - Recorded Latency)
50.000%    0.91ms
75.000%    1.35ms
90.000%    1.92ms
99.000%    2.91ms
99.900%    4.41ms
99.990%    5.47ms
99.999%    5.63ms
100.000%    5.64ms
``` 

### PUT
**Вставка уникального id на каждом запросе** 

`john@Batman:~/IdeaProjects/2024-highload-dht-1$ wrk -d 30 -t 1 -c 1 -R 8000 -L  -s ./src/main/java/ru/vk/itmo/test/osokindm/wrk_scripts/put_new.lua http://localhost:8080/v0/entity`

``` 
Running 30s test @ http://localhost:8080/v0/entity
1 threads and 1 connections
Thread calibration: mean lat.: 1.237ms, rate sampling interval: 10ms
Thread Stats   Avg      Stdev     Max   +/- Stdev
Latency     1.19ms    0.86ms  23.86ms   78.25%
Req/Sec     8.44k   839.27    20.60k    72.12%
Latency Distribution (HdrHistogram - Recorded Latency)
50.000%    1.10ms
75.000%    1.72ms
90.000%    2.05ms
99.000%    2.80ms
99.900%    9.42ms
99.990%   23.41ms
99.999%   23.85ms
100.000%   23.87ms

----------------------------------------------------------
239995 requests in 30.00s, 15.33MB read
Requests/sec:   7999.72
Transfer/sec:    523.42KB
```

Повторное выполнение запроса на пустой БД не приводит к существенному изменению результатов, в связи с тем, что созданные записи не просматриваются при вставке.
```
  Thread Stats   Avg      Stdev     Max   +/- Stdev
    Latency     1.47ms    1.53ms  26.43ms   93.23%
    Req/Sec     8.27k     1.05k   20.62k    84.83%
  Latency Distribution (HdrHistogram - Recorded Latency)
 50.000%    1.15ms
 75.000%    1.86ms
 90.000%    2.44ms
 99.000%    7.97ms
 99.900%   17.97ms
 99.990%   25.58ms
 99.999%   26.45ms
100.000%   26.45ms
```

[Flamegraph CPU](profiler/profile_put_8000.svg) 
Здесь оптимизация, касающаяся SelectorThread также применима, что и постараюсь реализовать в следующей дз.

[Flamegraph Alloc](profiler/profile_put_alloc_8000.svg)
В отличие от GET, здесь происходит намного меньше обращений к памяти.

**Замена существующего значения**

`john@Batman:~/IdeaProjects/2024-highload-dht-1$ wrk -d 30 -t 1 -c 1 -R 8000 -L  -s ./src/main/java/ru/vk/itmo/test/osokindm/wrk_scripts/put.lua http://localhost:8080/v0/entity`

```
Running 30s test @ http://localhost:8080/v0/entity
1 threads and 1 connections
Thread calibration: mean lat.: 1.326ms, rate sampling interval: 10ms
Thread Stats   Avg      Stdev     Max   +/- Stdev
Latency     1.17ms    1.14ms  33.95ms   97.95%
Req/Sec     8.44k     0.96k   29.00k    76.08%
Latency Distribution (HdrHistogram - Recorded Latency)
50.000%    1.09ms
75.000%    1.66ms
90.000%    1.98ms
99.000%    2.66ms
99.900%   20.54ms
99.990%   33.18ms
99.999%   33.95ms
100.000%   33.98ms
```
Если изменить скрипт lua и наполнять БД, обращаясь по ключам с уже существующими значениями, то можно заметить, что время работы также не изменилось  (1.19ms vs 1.17ms).


