# Этап 5. Async
## Setup
- WRK
  ```wrk -d 2m -t 1 -c 1 -R {value} -s {put/get}.lua -L http://localhost:8080/v0/entry```
- ASYNC-PROFILE
  ```asprof -d 130 -e cpu,alloc -f ./{name}.jfr ApplicationServer```
- CONVERTER
  ```java -cp lib/converter.jar jfr2flame {--alloc / --state default / --lock } ./{name}.jfr ./{name}.html```

See cpu flag issue, fixed from v3.0 async-profiler (https://github.com/async-profiler/async-profiler/issues/740)

## Content table
3 ноды в кластере. За основу взята референсная реализация и доработана асинхронным http клиентом. Измерения "до" пришлось измерить заново.

## PUT Research
### 14 thds - предыдущая точка разладки
sync
```
 50.000%    4.98ms
 75.000%    7.85ms
 90.000%   11.22ms
 99.000%   26.77ms
 99.900%   37.12ms
 99.990%   40.26ms
 99.999%   41.76ms
100.000%   42.78ms
```

async
Новая точка разладки - 110 thds
```
 50.000%    1.32ms
 75.000%    2.12ms
 90.000%    3.02ms
 99.000%   21.57ms
 99.900%   44.61ms
 99.990%   47.55ms
 99.999%   48.51ms
100.000%   49.38ms
```
120 thds
```
 50.000%    8.03s 
 75.000%   11.07s 
 90.000%   13.03s 
 99.000%   14.26s 
 99.900%   14.41s 
 99.990%   14.46s 
 99.999%   14.48s 
100.000%   14.49s 
```

CPU (sync)
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task5/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task5/asprof/put/cpu/png/sync_4.png)
CPU (async)
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task5/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task5/asprof/put/cpu/png/async_4.png)

На профиле есть аномалия, SharedRuntime занимает 26% сэмплов, SchedulableTask.run() занимает 23% CPU.
Насколько понял, в MultiExchange.responseAsyncImpl есть логика, завязанная на retries & redirect.
Внутри много регистраторов таймера и его остановок в случае timeout'а. Думаю, в нем проблема, можно попробовать увеличить timeout.
SchedulableTask.run() - по идее класс отвечает за постановку задач в очередь на потоки. Насколько я понимаю, он помогает
в обработке цепочек в completable future. Единственное, чего я не понимаю - это почему он такой же большой для sync флейм графа.

Появился ForkJoinWorkerThread. Он отвечает за выполнение ForkJoinTask CompletableFuture.
Когда вызывается метод MergeHandleResult.sendResult(), он выполняется в контексте этого потока.

PayloadThread.run уменьшился с 38% до 26%, тк теперь ожидание асинхронное и ноды выполняют больше полезной нагрузки и 
меньше простаивают.

MultiExchange.responseAsync переехала в Thread.run.
sync local handleRequest (4.51%) vs async handleAsReplica (5%)

ALLOC (sync)
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task5/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task5/asprof/put/alloc/png/sync_4.png)
ALLOC (async)
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task5/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task5/asprof/put/alloc/png/async_4.png)

Добавилась обработка ConcurrentLinkedQueue (36 + 116 сэмплов, 0.06 %+ 0.18%).

Также появился появился ForkJoinWorkerThread, который занимает 1.66%.

HttpClientFacade.send для синк подхода ~23000 сэмплов (44.45%), для асинк подхода HttpClientFacade.sendAsync ~8000 (12.14%).

LOCK (sync)
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task5/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task5/asprof/put/lock/png/sync_4.png)
LOCK (async)
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task5/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task5/asprof/put/lock/png/async_4.png)

HttpClientFacade.send для синк подхода ~1_300_000 сэмплов (37.37%), для асинк подхода HttpClientFacade.sendAsync ~1_130_000 (25.29%) -
MultiExchange.responseAsync переехала в Thread.run и выросла с 22.60% до 29.69%. В сумме стало больше, что логично, тк 
асинхронных операций стало больше.

## GET Research
### 13 thds - предыдущая точка разладки
sync
```
 50.000%    3.59ms
 75.000%    5.56ms
 90.000%    8.17ms
 99.000%   12.96ms
 99.900%   15.90ms
 99.990%   17.58ms
 99.999%   18.74ms
100.000%   19.60ms
```

asyc
Новая точка разладки - 60 thds
```
 50.000%    1.39ms
 75.000%    1.82ms
 90.000%    2.29ms
 99.000%    3.38ms
 99.900%    5.80ms
 99.990%   12.28ms
 99.999%   13.49ms
100.000%   15.02ms
```

CPU (sync)
![get](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task5/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task5/asprof/get/cpu/png/sync_4.png)
CPU (async)
![get](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task5/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task5/asprof/get/cpu/png/async_4.png)

В асинхронной версии processHttpBuffer() занимает почти 40% CPU сэмплов, а в синхронной -- меньше 4%.
processRead -> processHttpBuffer -> 22% просидели в HttpSession.closing -> это триггерило exception Bad request.
Но я подозреваю, что это также связано с маленьким timeout'ом, для http клиента.

ALLOC (sync)
![get](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task5/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task5/asprof/get/alloc/png/sync_4.png)
ALLOC (async)
![get](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task5/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task5/asprof/get/alloc/png/async_4.png)

MultiExchange.responseAsync переехала в Thread.run и почти не изменилась (30% vs 31%).

LOCK (sync)
![get](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task5/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task5/asprof/get/lock/png/sync_4.png)
LOCK (async)
![get](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task5/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task5/asprof/get/lock/png/async_4.png)

HttpClientFacade.send для синк подхода ~1_550_000 сэмплов (36.97%), для асинк подхода HttpClientFacade.sendAsync ~1_200_000 (24.17%).
MultiExchange.responseAsync 11.38% -> 3.33%. Кажется, немного аномальная ситуация, тк я не ожидал такого изменения. Вероятно
связано с аномальным простоем в processHttpBuffer.
