# Этап 3. Шардирование

### Хэширование
Изначально в реализации я использовала алгоритм Рандеву хэширования, вычисляя hashCode от суммы строк url и id (ключа).
Однако, распределение оказалось совсем неравномерным, большинство запросов улетало только на одну из нод. 
Для оценки производительности посмотрим на среднюю latency при put запросах:

| RPS   | Avg Latency |
|-------|-------------|
| 9700  | 1.50ms      | 
| 9800  | 5.40ms      |
| 10000 | 1.57s       |

Печальная картина, попробуем поменять алгоритм вычисления хэша. Достаточно всего лишь заменить hashCode на murmur3 и распределение становится равномерным.
Производительность тоже улучшается

| RPS   | Avg Latency |
|-------|-------------|
| 20000 | 1.69ms      |
| 28000 | 5.92ms      |
| 32000 | 6.42ms      |
| 35000 | 933.82ms    |

Средняя latency после 20000 rps растет, но до ~35000 rps остается примелемым и высокие перцентили не превоходят 50-60ms.

Проанализируем работу PUT для 20000 rps:
```
 wrk2 -d 30 -t 1 -c 1 -R 20000 -L -s put.lua http://127.0.0.1:8080
  Thread Stats   Avg      Stdev     Max   +/- Stdev
    Latency     1.98ms    2.36ms  44.38ms   96.04%
    Req/Sec    21.11k     2.61k   34.89k    79.63%
  Latency Distribution (HdrHistogram - Recorded Latency)
 50.000%    1.55ms
 90.000%    3.10ms
 99.000%    9.93ms
 99.900%   33.44ms
100.000%   44.42ms
```
[CPU profile](data/stage3/profile-put-20000.html)

Из графа видим, что на хэширование ушло всего 10 сэмплов (0.11%), так что операция 
далеко не самая тяжелая, и гораздо больше влияет работа ThreadPoolExecutor (41% сэмплов на getTask) или редирект запроса на другую ноду
(почти 12%)

[Alloc profile](data/stage3/profile-put-20000-alloc.html)

На редирект запроса уходит 50% сэмплов, а если точнее, то 46% - send из HttpClient. Еще около 30% уходит на AsyncReceiver, selectorManager.run() и execute
внутри HttpClient. Итого, около 70% аллокаций производится из-за добавления взаимодействия по сети.

[Lock profile](data/stage3/profile-put-20000-lock.html)

Основное время ожидания блокировок приходится на HttpClient (SequentialScheduler.run(), HttpClientImpl.send(), SelectorManager.run())

Теперь посмотрим на GET

| RPS   | Avg Latency |
|-------|-------------|
| 25000 |  1.65ms     |
| 30000 | 2.46ms      |
| 35000 | 19.83ms     |

На 35000 rps начинают появляться ошибки.

Проанализируем работу GET для 25000 rps:
```
wrk2 -d 30 -t 1 -c 64 -R 25000 -L -s get.lua http://127.0.0.1:8080
  Thread Stats   Avg      Stdev     Max   +/- Stdev
    Latency     1.65ms    0.89ms  12.42ms   70.71%
    Req/Sec    26.39k     2.12k   33.78k    68.71%
  Latency Distribution (HdrHistogram - Recorded Latency)
 50.000%    1.50ms
 90.000%    2.87ms
 99.000%    4.24ms
 99.990%    7.30ms
 99.999%    9.12ms
100.000%   12.43ms
```

[CPU profile](data/stage3/profile-get-25000.html)

12% сэмплов на HttpClientImpl.send + ~18% на SequentialScheduler.run() и SelectorManager.run(). Это очень много по сравнению с 0.6% на бизнес-логику Dao

[Alloc profile](data/stage3/profile-get-25000-alloc.html)

Как и с PUT - почти половина сэмплов на send из HttpClient. Еще около 30% уходит на SequentialScheduler.run() и selectorManager.run()
внутри HttpClient.

[Lock profile](data/stage3/profile-get-25000-lock.html)

И снова картина аналогичная PUT, почти все время ожидания блокировок приходится на HttpClient (SequentialScheduler.run(), HttpClientImpl.send(), SelectorManager.run()).

### Сравнение с этапом 2

//TODO
