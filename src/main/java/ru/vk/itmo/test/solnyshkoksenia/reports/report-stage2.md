# Report stage 2
## Подбор параметров `ExecutorService`

Посмотрим, стал ли сервер работать лучше после того, как мы разгрузили `SelectorThread`ы. Будем изменять параметры и 
сравнивать с предыдущим этапом с помощью нагрузочного тестирования. На момент начала тестирования база заполнена 
примерно на 230mb. Для тестирования используем скрипт [`put.lua`](../wrk_scripts/put.lua). В качестве `id` используются
рандомные строки длины [10,100], в качестве `body` - рандомные строки длины 300.

### SynchronousQueue

```java
int corePoolSize = 1
int maximumPoolSize = Integer.MAX_VALUE
BlockingQueue<Runnable> workQueue = new SynchronousQueue<>()
```

В качестве очереди используем `SynchronousQueue`, а количество воркеров разрешим любое. Таким образом, когда будет
прилетать новая задачка, она будет делегироваться любому активному треду или сразу будет создаваться новый тред. Сравним
с последовательным сервером на одних параметрах.

#### `PUT` requests, RPS: 5000, Connections: 64, Threads: 64, Duration: 30s

Ожидаемо, асинхронный сервер обрабатывает запросы быстрее. Однако с таким подходом однажды мы достигнем максимального
количества потоков и получим reject. Попробуем взять другую очередь.

![image](images/stage2/cmp-1-max-syn-put-d30-t64-c64-R5000.png)

### LinkedBlockingQueue

```java
int corePoolSize = 1
BlockingQueue<Runnable> workQueue = new LinkedBlockingQueue<>()
```

Так как `LinkedBlockingQueue` в данном случае не ограничена по размеру, то она никогда не заполнится, а значит 
`maxPoolSize` не имеет значения, всегда будет использоваться фиксированное количество тредов `corePoolSize`.

#### `PUT` requests, RPS: 5000, Connections: 64, Threads: 64, Duration: 30s

Ожидаемо этот вариант работает медленнее, чем все рассмотренные прежде, потому что, он пускает все задачи в 1 поток,
тогда как в предыдущем этапе задачи выполняются в тредах-селекторах, которых фиксированное количество, а в случае с
`SynchronousQueue` тредов вообще сколько угодно.

![image](images/stage2/cmp-syn-lin-put-d30-t64-c64-R5000.png)

Заметим, что если не указывать явно количество селекторов при создании [`MyHttpServer`](../MyHttpServer.java), в
конструкторе `Server` их аллоцируется столько же, сколько и доступных процессоров. Поставим столько же воркеров.

```java
int corePoolSize = Runtime.getRuntime().availableProcessors()
BlockingQueue<Runnable> workQueue = new LinkedBlockingQueue<>()
```

Как и ожидалось, этот вариант будет работать примерно так же, как и вариант из предыдущего этапа.

![image](images/stage2/cmp-lin-lin-put-d30-t64-c64-R5000.png)

Сделаем вывод, что чем больше `corePoolSize`, тем лучше latency. Однако вариант с неограниченной очередью нас тоже не
устраивает, потому что однажды мы можем получить `OutOfMemoryException`. Рассмотрим очереди с capacity.

### LinkedBlockingQueue with capacity and fair ArrayBlockingQueue

Если мы ограничиваем размер очереди, у нас появляется новый сценарий - максимальное количество тредов загружено и 
очередь заполнена. В таком случае введём `RejectedExecutionHandler` чтобы корректно обрабатывать такие случаи. Завернём
`Runnable` в `Task`, чтобы передавать в `ExecutorService` сессию, а в `reject()` будем отправлять в сессию
`Response.PAYMENT_REQUIRED`.


















[//]: # (### `PUT` requests, RPS: 100, Connections: 64, Threads: 64, Duration: 30s)

[//]: # ()
[//]: # (На низких RPS видно, что асинхронный сервер обрабатывает запросы быстрее)

[//]: # ()
[//]: # (![image]&#40;images/stage2/cmp-put-d30-t64-c64-R100.png&#41;)

[//]: # ()
[//]: # (### `PUT` requests, RPS: 5000, Connections: 64, Threads: 64, Duration: 30s)

[//]: # ()
[//]: # (Интересно, что при текущих параметрах, асинхронный и последовательный сервер работают почти одинаково, но <1% запросов)

[//]: # (последовательный сервер обрабатывает быстрее. `ThreadPoolExecutor` добавляет новые потоки, когда `workQueue`)

[//]: # (переполняется. Попробуем)

[//]: # ()
[//]: # (![image]&#40;images/stage2/cmp-put-d30-t64-c64-R5000.png&#41;)

[//]: # (Using large queues and small pools minimizes CPU usage, OS resources, and context-switching overhead, but can 
lead to artificially low throughput. If tasks frequently block &#40;for example if they are I/O bound&#41;, a system may
be able to schedule time for more threads than you otherwise allow. Use of small queues generally requires larger pool 
sizes, which keeps CPUs busier but may encounter unacceptable scheduling overhead, which also decreases throughput.)