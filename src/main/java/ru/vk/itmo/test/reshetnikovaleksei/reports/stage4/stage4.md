# Stage 4

from=3, ack=2 (по дефолту)

запускаю 3 инстанса в разных потоках

## PUT TEST

#### Сравнение со stage 3

```shell
./wrk -d 30 -t 4 -c 32 -R 15000 -L -s src/main/java/ru/vk/itmo/test/reshetnikovaleksei/lua/put.lua  http://localhost:8080

 Latency |  стало |  было
 50.000% | 6.29s  |   1.37ms  
 75.000% | 7.17s  |   1.96ms  
 90.000% | 7.56s  |   3.83ms  
 99.000% | 7.82s  | 464.13ms  
 99.900% | 7.86s  | 503.04ms  
 99.990% | 7.88s  | 505.86ms  
 99.999% | 7.89s  | 507.65ms  
100.000% | 7.89s  | 508.16ms  
```

В среднем производительность уменьшилась примерно в 15 раз, что в целом очевидно, так как запрос теперь рассылается 
по нескольким нодам, и теперь нужно ждать ответа от всех нод: проксирование происходит сразу по всем узлам.  

#### Профилирование

[Allocation profile](put_alloc.html)

- 89% аллокаций на `ThreadPoolExecutor.runWorker` - отправка и ожидание запросов от соседних нод происходят здесь
  - 19.73% аллокаций уходят на метод `sendRequestAndGetResponses`, который отправляет запросы в другие ноды и ждет ответы
  - 0.74% аллокаций уходят на `invokeLocal` - локальная обработка запроса
  - 1.45% аллокаций на отправку ответа
  - остальное из `ThreadPoolExecutor.runWorker` - обработка ответов из соседних нод
- 4% аллокаций на `SelectorManager.run`
- 6.89% на `SelectorThread.run`

[CPU profile](put_cpu.html)

- 65.29% спу на `ThreadPoolExecutor.runWorker`
  - 10.67% спу на `sendRequestAndGetResponses`
  - 0.37% спу на `invokeLocal`
  - 3.87% спу на отправку ответа
  - остальное из `ThreadPoolExecutor.runWorker` - обработка ответов из соседних нод
- 6.98% спу на `ForkJoinWorkerThread.run`
- 7.6% аллокаций на `SelectorManager.run`
- 14.43% на `SelectorThread.run`

[Lock profile](put_lock.html)

- 5.61% локов на `SelectorManager.run`
- 94.23% локов на `ThreadPoolExecutor.runWorker`

## GET TEST

#### Сравнение с stage3

```shell
./wrk -d 30 -t 4 -c 32 -R 15000 -L -s src/main/java/ru/vk/itmo/test/reshetnikovaleksei/lua/get.lua http://localhost:8080

 Latency |  стало   |  было
 50.000% | 436.99ms | 1.00ms  
 75.000% | 640.00ms | 1.36ms  
 90.000% | 729.09ms | 1.64ms  
 99.000% | 997.38ms | 2.12ms  
 99.900% |   1.02s  | 2.45ms  
 99.990% |   1.03s  | 2.79ms  
 99.999% |   1.04s  | 3.25ms  
100.000% |   1.04s  | 3.51ms 
```

Аналогично с `PUT` наблюдается сильная деградация производительности по той же причине

#### Профилирование

[Allocation profile](get_alloc.html)

[CPU profile](get_cpu.html)

[Lock profile](get_lock.html)

По числам при `GET` примерно такая же картина

## Вывод

Репликация очень сильно деградировала производительность, но за это сервис стал более надежным за счет того, что у нас
теперь есть возможность указывать минимальное кол-во нод, которые должны отправить положительный ответ
