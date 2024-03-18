# Этап 2. Executor pool
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
Перед сравнением для старой реализации (без шардирования) значения были переизмерены для повышения корректности сравнения в
одинаковых условиях. 

## PUT Research
### 900 thds 
Без шардирования
```
 50.000%    0.93ms
 75.000%    1.34ms
 90.000%    2.03ms
 99.000%    7.28ms
 99.900%   16.51ms
 99.990%   24.88ms
 99.999%   34.30ms
100.000%   39.62ms
```

С шардированием
```
 50.000%   40.86s
 75.000%    0.99m
 90.000%    1.17m
 99.000%    1.28m
 99.900%    1.29m
 99.990%    1.30m
 99.999%    1.30m
100.000%    1.30m
```
### 300 thds - новая точка разладки
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

CPU
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task3/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task3/asprof/put/cpu/png/300_000rps.png)
Из-за ожидания задачи воркеры много простаивают - ~14000 сэмплов метода park()
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task3/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task3/asprof/put/cpu/png/1hash.png)
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task3/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task3/asprof/put/cpu/png/2hash.png)

Рассчет хэша происходит 2 раза - проверка на необходимость проксирования запроса (532 сэмпла, 0.58%) + на само проксирование (126 сэмплов, 0.14).
Второй раз более оптимизирован, поэтому не сильно жалко пожертвовать процессорное время воимя инкапсуляции логики проксирующего сервиса.

ALLOC
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task3/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task3/asprof/put/alloc/png/300_000rps.png)

Значительные затраты ресурсов на обработку запросов через HttpClient - отправка запросов, управление соединениями, работа с ответами.
В общем 42000 сэмплов, 52.47% на прокирование запроса.

Для разгрузки воркеров и повышения производительности системы можно перейти к асинхронному взаимодействию с HttpClient.

LOCK
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task3/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task3/asprof/put/lock/png/300_000rps.png)
43% сэмплов блокировок из-за SelectorManager'а внутри http клиента - при регистрации асинхронных эвентов через метод register.
Этот метод использует блокировку для атомарной проверки состояния SelectorManager'а и регистрации нового ивента.

## GET Research
Без шардирования
```
 50.000%    1.52ms
 75.000%    2.03ms
 90.000%    2.51ms
 99.000%    3.57ms
 99.900%    4.94ms
 99.990%   12.41ms
 99.999%   17.23ms
100.000%   26.03ms
```

С шардированием
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
Значение процентилей уменьшилось, особенно приятно для 100го процентиля - меньше на 11ms.

CPU
![get](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task3/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task3/asprof/get/cpu/png/24_000rps.png)

ALLOC
![get](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task3/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task3/asprof/get/alloc/png/24_000rps.png)

LOCK
![get](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task3/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task3/asprof/get/lock/png/24_000rps.png)
Выросло влияние tryAsyncReceive по сравнению с put запросами, тк теперь в ответе от проксирующей ноды приходит еще и body.
![get](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task3/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task3/asprof/get/lock/png/receive.png)
