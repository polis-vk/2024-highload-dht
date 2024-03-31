# Этап 4. Replication
## Setup
- WRK
  ```wrk -d 2m -t 1 -c 1 -R {value} -s {put/get}.lua -L http://localhost:8080/v0/entry```
- ASYNC-PROFILE
  ```asprof -d 130 -e cpu,alloc -f ./{name}.jfr ApplicationServer```
- CONVERTER
  ```java -cp lib/converter.jar jfr2flame {--alloc / --state default / --lock } ./{name}.jfr ./{name}.html```

See cpu flag issue, fixed from v3.0 async-profiler (https://github.com/async-profiler/async-profiler/issues/740)

## Content table
3 ноды в кластере.
Для удобства работы с параметрами запороса пришлось переписать реализацию с раздельными методами под каждый тип запроса(@Path)
на реализацию, использующую единую точку обработки со switch'ом под каждый тип запроса. 

## PUT Research
### 300 thds - предыдущая точка разладки
Без репликации
```
 50.000%    1.28ms
 75.000%    2.49ms
 90.000%   71.29ms
 99.000%  149.63ms
 99.900%  162.82ms
 99.990%  167.68ms
 99.999%  172.16ms
100.000%  174.08ms
```
С репликацией
```
 50.000%   20.77s
 75.000%   28.03s
 90.000%   32.51s
 99.000%   35.16s
 99.900%   35.42s
 99.990%   35.45s
 99.999%   35.49s
100.000%   35.49s
```
Новая точка разладки - 115 thds
```
 50.000%    1.13ms
 75.000%    1.59ms
 90.000%    2.10ms
 99.000%    3.41ms
 99.900%    6.64ms
 99.990%   10.10ms
 99.999%   11.50ms
100.000%   14.18ms
```
120 thds
```
 50.000%    1.76ms
 75.000%  212.22ms
 90.000%  540.67ms
 99.000%  729.60ms
 99.900%  769.02ms
 99.990%  787.46ms
 99.999%  797.18ms
100.000%  798.72ms
```

CPU
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task4/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task4/asprof/put/cpu/png/h_p115_000rps.png)


Рассчет хэша происходит 2 раза - проверка на необходимость проксирования запроса (532 сэмпла, 0.58%) + на само проксирование (126 сэмплов, 0.14).
Второй раз более оптимизирован, поэтому не сильно жалко пожертвовать процессорное время воимя инкапсуляции логики проксирующего сервиса.

ALLOC
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task4/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task4/asprof/put/alloc/png/h_p115_000rps.png)

Значительные затраты ресурсов на обработку запросов через HttpClient - отправка запросов, управление соединениями, работа с ответами.
В общем 42000 сэмплов, 52.47% на прокирование запроса.

Для разгрузки воркеров и повышения производительности системы можно перейти к асинхронному взаимодействию с HttpClient.

LOCK
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task4/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task4/asprof/put/lock/png/h_p115_000rps.png)
43% сэмплов блокировок из-за SelectorManager'а внутри http клиента - при регистрации асинхронных эвентов через метод register.
Этот метод использует блокировку для атомарной проверки состояния SelectorManager'а и регистрации нового ивента.

## GET Research
### 25 thds - предыдущая точка разладки
Без репликации
```
 50.000%    1.21ms
 75.000%    1.64ms
 90.000%    2.02ms
 99.000%    2.61ms
 99.900%    3.45ms
 99.990%   10.19ms
 99.999%   13.54ms
100.000%   15.22ms
```
С репликацией
```
 50.000%   15.73s
 75.000%   24.31s
 90.000%   29.51s
 99.000%   32.62s
 99.900%   33.26s
 99.990%   33.41s
 99.999%   33.49s
100.000%   33.49s
```
Новая точка разладки - 10 thds
```
 50.000%    1.01ms
 75.000%    1.51ms
 90.000%    6.38ms
 99.000%   50.24ms
 99.900%   97.66ms
 99.990%  133.25ms
 99.999%  185.73ms
100.000%  202.11ms
```
CPU
![get](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task4/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task4/asprof/get/cpu/png/h_g10_000rps.png)
Однако, после анализа flame графа я нашел ошибку в параллельной обработке и исправил ее. Теперь сначала отправляются все
запросы, а после ожидаются все их респонзы, с использованием CompletableFuture.allOf().

Новая точка разладки - 50 thds
```
 50.000%    1.21ms
 75.000%    1.65ms
 90.000%    2.04ms
 99.000%    7.03ms
 99.900%   38.97ms
 99.990%   47.33ms
 99.999%   52.06ms
100.000%   54.46ms
```

CPU
![get](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task4/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task4/asprof/get/cpu/png/h_async_g50_000rps.png)

ALLOC
![get](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task4/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task4/asprof/get/alloc/png/h_async_g50_000rps.png)

LOCK
![get](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task4/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task4/asprof/get/lock/png/h_async_g50_000rps.png)
