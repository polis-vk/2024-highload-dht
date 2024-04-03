# Report 4

Профилирование проходило при 27к rps

## put

(изначально пустая база)

### ack 1 from 1 (50k rps)

| percentage | latency |
-----------|-----------
| 50.000% |    1.71ms |
| 75.000% |    2.35ms |
| 90.000% |    3.10ms |
| 99.000% |    4.47ms |
| 99.900% |    5.98ms |
| 99.990% |    6.90ms |
| 99.999% |    7.34ms |
| 100.000% |    7.83ms |

### ack 1 from 1 (27k rps)

| percentage | latency |
-----------|-----------
| 50.000% |    1.66ms |
| 75.000% |    2.35ms |
| 90.000% |    2.94ms |
| 99.000% |    4.22ms |
| 99.900% |    5.60ms |
| 99.990% |    8.29ms |
| 99.999% |    9.51ms |
| 100.000% |    9.99ms |

### ack 2 from 3 (quorum) (27k rps)

| percentage | latency |
-----------|-----------
| 50.000% |    1.65ms |
| 75.000% |    2.31ms |
| 90.000% |    3.06ms |
| 99.000% |    7.08ms |
| 99.900% |   11.71ms |
| 99.990% |   18.45ms |
| 99.999% |   19.39ms |
| 100.000% |   19.77ms |

### ack 3 from 3 (27 rps)

| percentage | latency |
-----------|-----------
| 50.000% |    2.15ms |
| 75.000% |    2.84ms |
| 90.000% |    3.88ms |
| 99.000% |    6.47ms |
| 99.900% |    8.37ms |
| 99.990% |    9.55ms |
| 99.999% |    9.93ms |
| 100.000% |    9.97ms |

[графики](https://disk.yandex.ru/i/6dcrF4kLb5jMvg)

### Profiling

#### cpu

[ack 1 from 1](https://disk.yandex.ru/d/QGEJq48cdQzkVw)

[ack 2 from 3](https://disk.yandex.ru/d/Evdm1hsgjZziwA)

1) Read и write в session стали занимать на около 50 процентов больше
2) GC стал занимать не 0.5 процента, а 5,5 процента.

#### alloc

[ack 1 from 1](https://disk.yandex.ru/d/aEcJx0mOxXu2VQ)

[ack 2 from 3](https://disk.yandex.ru/d/gE_920JbDpDPHQ)

`byte[]` в response header стал занимать на 2 процента больше. 
Мое предположение, что относительно - ничего не изменилось, изменилось только абсолютно.

#### lock

[ack 1 from 1](https://disk.yandex.ru/d/2DzpfBWBrrpeAA)

[ack 2 from 3](https://disk.yandex.ru/d/CURf22YYQ2xZoQ)

На 7 процентов стали больше занимать блокировки на workerах (теперь воркеры ждут ответ)

## get

Изначально в базе около 150 тысяч ключей (около 100mb)

### ack 1 from 1 (50k rps)

| percentage | latency |
-----------|-----------
| 50.000% |    1.56ms |
| 75.000% |    2.02ms |
| 90.000% |    2.68ms |
| 99.000% |    3.56ms |
| 99.900% |    3.96ms |
| 99.990% |    4.23ms |
| 99.999% |    4.37ms |
| 100.000% |    4.50ms |


### ack 1 from 1 (27k rps)

| percentage | latency |
-----------|-----------
| 50.000% |    1.66ms |
| 75.000% |    2.36ms |
| 90.000% |    2.93ms |
| 99.000% |    4.00ms |
| 99.900% |    4.97ms |
| 99.990% |    5.52ms |
| 99.999% |    5.96ms |
| 100.000% |    6.22ms |

### ack 2 from 3 (quorum) (27k rps)

| percentage | latency |
-----------|-----------
| 50.000% |   1.84ms |
| 75.000% |   2.42ms |
| 90.000% |   3.06ms |
| 99.000%  |  7.62ms |
| 99.900%  | 10.91ms |
| 99.990% |  11.70ms |
| 99.999% |  12.54ms |
| 100.000% |  12.94ms |

### ack 3 from 3 (27k rps)

| percentage | latency |
-----------|-----------
| 50.000%  |  1.97ms |
| 75.000% |   2.48ms |
| 90.000% |   2.96ms |
| 99.000% |   5.05ms |
| 99.900% |   6.93ms |
| 99.990% |   7.57ms |
| 99.999% |   7.91ms |
| 100.000% |    8.15ms |

[графики](https://disk.yandex.ru/i/ZBE6CLmRVgAXtA)

### Profiling

#### cpu

[ack 1 from 1](https://disk.yandex.ru/d/EeB63VIYR-LD3Q)

[ack 2 from 3](https://disk.yandex.ru/d/yhoW4bpXt3TTkg)

На 4 процента стали больше стал занимать метод `park` внутри очереди workerов.

#### alloc

[ack 1 from 1](https://disk.yandex.ru/d/jetkLxwDu37oiQ)

[ack 2 from 3](https://disk.yandex.ru/d/hO1pUqKH95r-bw)

На 7 процентов стало больше занимать создание сегментов.

#### lock

[ack 1 from 1](https://disk.yandex.ru/d/Vz86i4F9nAqJ0g)

[ack 2 from 3](https://disk.yandex.ru/d/-ArqWmgKhJXlYA)

На 10 процентов стала больше блокировка на ответы сессии.