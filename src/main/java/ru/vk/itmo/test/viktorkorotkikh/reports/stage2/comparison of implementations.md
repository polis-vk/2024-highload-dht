# Stage 2 - Сравнение virtual threads, thread pool + LinkedBlockingQueue, thread pool + ArrayBlockingQueue

## 1 thread, 1 connection

### PUT

| [virtualThreads](PUT-1connection-virtualthreads-60k.txt)                                                                                                                                                                                                                                                                                                                                                               | [TP + LinkedBQ](PUT-1connection-pool-linkedqueue-60k.txt)                                                                                                                                                                                                                                                                                                                                                              | [TP + ArrayBQ](PUT-1connection-pool-arrayqueue-60k.txt)                                                                                                                                                                                                                                                                                                                                                                |
|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| <pre>  Thread Stats   Avg      Stdev     Max   +/- Stdev<br>    Latency     7.07ms   22.01ms 129.22ms   92.73%<br>    Req/Sec    62.97k     4.79k   77.11k    74.80%<br>  Latency Distribution (HdrHistogram - Recorded Latency)<br> 50.000%  709.00us<br> 75.000%    1.05ms<br> 90.000%   16.33ms<br> 99.000%  120.51ms<br> 99.900%  128.06ms<br> 99.990%  129.09ms<br> 99.999%  129.28ms<br>100.000%  129.28ms</pre> | <pre>  Thread Stats   Avg      Stdev     Max   +/- Stdev<br>    Latency     8.30ms   13.79ms  69.25ms   83.60%<br>    Req/Sec    63.14k     5.03k   75.22k    66.79%<br>  Latency Distribution (HdrHistogram - Recorded Latency)<br> 50.000%    1.13ms<br> 75.000%    9.93ms<br> 90.000%   28.21ms<br> 99.000%   62.91ms<br> 99.900%   68.80ms<br> 99.990%   69.25ms<br> 99.999%   69.25ms<br>100.000%   69.31ms</pre> | <pre>  Thread Stats   Avg      Stdev     Max   +/- Stdev<br>    Latency     2.71ms    6.28ms  44.96ms   92.49%<br>    Req/Sec    63.18k     4.91k   74.78k    64.63%<br>  Latency Distribution (HdrHistogram - Recorded Latency)<br> 50.000%  827.00us<br> 75.000%    1.24ms<br> 90.000%    5.53ms<br> 99.000%   34.43ms<br> 99.900%   44.03ms<br> 99.990%   44.90ms<br> 99.999%   44.96ms<br>100.000%   44.99ms</pre> |
| ![PUT-1connection-virtualthreads-60k-histogram.png](PUT-1connection-virtualthreads-60k-histogram.png)                                                                                                                                                                                                                                                                                                                  | ![PUT-1connection-pool-linkedqueue-60k-histogram.png](PUT-1connection-pool-linkedqueue-60k-histogram.png)                                                                                                                                                                                                                                                                                                              | ![PUT-1connection-pool-arrayqueue-60k-histogram.png](PUT-1connection-pool-arrayqueue-60k-histogram.png)                                                                                                                                                                                                                                                                                                                |

По гистограмме хорошо видно, что virtualThreads примерно около 88% имеют резкий рост задержки.
Между 75% и 90% разница в 16 раз.

ThreadPool показывает лучшие результаты - гистограмма более плавная. Однако по задержкам ArrayBlockingQueue выигрывает у
LinkedBlockingQueue.

#### Virtual Threads CPU

У виртуальных потоков большую часть времени занимает ожидание задачи. Вероятно это связано с устройством ForkJoinPool,
на
базе которого работает `Executors.newVirtualThreadPerTaskExecutor()`.

![PUT-1connection-virtualthreads-60k-cpu.png](PUT-1connection-virtualthreads-60k-cpu.png)

#### ThreadPool LinkedBlockingQueue CPU

![PUT-1connection-pool-linkedqueue-60k-cpu.png](PUT-1connection-pool-linkedqueue-60k-cpu.png)

#### ThreadPool ArrayBlockingQueue CPU

![PUT-1connection-pool-arrayqueue-60k-cpu.png](PUT-1connection-pool-arrayqueue-60k-cpu.png)

По профилю CPU видно, почему arrayqueue работает быстрее, чем linkedqueue - меньше времени затрачивается на получение
задачи из очереди: 11.85% против 8.76%.

![PUT-1connection-pool-linkedqueue-60k-cpu-getTask.png](PUT-1connection-pool-linkedqueue-60k-cpu-getTask.png)

![PUT-1connection-pool-arrayqueue-60k-cpu-getTask.png](PUT-1connection-pool-arrayqueue-60k-cpu-getTask.png)

В остальном профили одинаковые, что логично, так как мы поменяли тут лишь очередь.

#### Virtual Threads Alloc

![GET-1connection-virtualthreads-alloc.png](GET-1connection-virtualthreads-alloc.png)

#### ThreadPool LinkedBlockingQueue Alloc

![PUT-1connection-pool-linkedqueue-60k-alloc.png](PUT-1connection-pool-linkedqueue-60k-alloc.png)

#### ThreadPool ArrayBlockingQueue Alloc

![PUT-1connection-pool-arrayqueue-60k-alloc.png](PUT-1connection-pool-arrayqueue-60k-alloc.png)

У ThreadPool executor нет накладных расходов, кроме создания объекта ConditionNode:

![PUT-1connection-pool-queue-alloc.png](PUT-1connection-pool-queue-alloc.png)

Virtual Threads требуют 20% аллокаций на создание виртуального потока:

![PUT-1connection-virtualthreads-alloc-1.png](PUT-1connection-virtualthreads-alloc-1.png)

#### Virtual Threads Lock

![PUT-1connection-virtualthreads-lock.png](PUT-1connection-virtualthreads-lock.png)

#### ThreadPool LinkedBlockingQueue Lock

![PUT-1connection-pool-linkedqueue-lock.png](PUT-1connection-pool-linkedqueue-lock.png)

#### ThreadPool ArrayBlockingQueue Lock

![PUT-1connection-pool-arrayqueue-lock.png](PUT-1connection-pool-arrayqueue-lock.png)

ArrayBlockingQueue тратит больше времени на блокировку при добавлении в очередь. Virtual Threads кроме общих блокировок
на LSMCustomSession (synchronized методы) больше нигде не блокируется.

### GET

| [virtualThreads](GET-1connection-virtualthreads.txt)                                                                                                                                                                                                                                                                                                                                                                 | [TP + LinkedBQ](GET-1connection-pool-linkedqueue-60k.txt)                                                                                                                                                                                                                                                                                                                                                              | [TP + ArrayBQ](GET-1connection-pool-arrayqueue-60k.txt)                                                                                                                                                                                                                                                                                                                                                                |
|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| <pre>  Thread Stats   Avg      Stdev     Max   +/- Stdev<br>    Latency   622.97us  665.92us  20.13ms   98.93%<br>    Req/Sec    31.66k     2.30k   47.89k    65.61%<br>  Latency Distribution (HdrHistogram - Recorded Latency)<br> 50.000%  594.00us<br> 75.000%    0.88ms<br> 90.000%    1.05ms<br> 99.000%    1.34ms<br> 99.900%   11.77ms<br> 99.990%   19.49ms<br> 99.999%   20.11ms<br>100.000%   20.14ms</pre> | <pre></pre> | <pre></pre> |
| ![GET-1connection-virtualthreads-60k-histogram.png](GET-1connection-virtualthreads-histogram.png)                                                                                                                                                                                                                                                                                                                  | ![GET-1connection-pool-linkedqueue-60k-histogram.png](GET-1connection-pool-linkedqueue-60k-histogram.png)                                                                                                                                                                                                                                                                                                              | ![GET-1connection-pool-arrayqueue-60k-histogram.png](GET-1connection-pool-arrayqueue-60k-histogram.png)                                                                                                                                                                                                                                                                                                                |

По гистограмме хорошо видно, что virtualThreads примерно около 88% имеют резкий рост задержки.
Между 75% и 90% разница в 16 раз.

ThreadPool показывает лучшие результаты - гистограмма более плавная. Однако по задержкам ArrayBlockingQueue выигрывает у
LinkedBlockingQueue.

#### Virtual Threads CPU

Снова у виртуальных потоков немалую часть времени занимает ожидание задачи.

![GET-1connection-virtualthreads-cpu.png](GET-1connection-virtualthreads-cpu.png)

#### ThreadPool LinkedBlockingQueue CPU

![GET-1connection-pool-linkedqueue-60k-cpu.png](GET-1connection-pool-linkedqueue-cpu.png)

#### ThreadPool ArrayBlockingQueue CPU

![GET-1connection-pool-arrayqueue-60k-cpu.png](GET-1connection-pool-arrayqueue-cpu.png)

По профилю CPU не видно, почему arrayqueue работает быстрее, чем linkedqueue. Но на профиле локов станет всё понятно.

#### Virtual Threads Alloc

![GET-1connection-virtualthreads-alloc.png](GET-1connection-virtualthreads-alloc.png)

#### ThreadPool LinkedBlockingQueue Alloc

![GET-1connection-pool-linkedqueue-60k-alloc.png](GET-1connection-pool-linkedqueue-alloc.png)

#### ThreadPool ArrayBlockingQueue CPU

![GET-1connection-pool-arrayqueue-60k-alloc.png](GET-1connection-pool-arrayqueue-alloc.png)

У ThreadPool executor также из изменений создание объекта ConditionNode

Virtual Threads требуют 30% аллокаций на создание виртуального потока.

#### Virtual Threads Lock

![GET-1connection-virtualthreads-lock.png](GET-1connection-virtualthreads-lock.png)

#### ThreadPool LinkedBlockingQueue Lock

![GET-1connection-pool-linkedqueue-lock.png](GET-1connection-pool-linkedqueue-lock.png)

#### ThreadPool ArrayBlockingQueue Lock

![GET-1connection-pool-arrayqueue-lock.png](GET-1connection-pool-arrayqueue-lock.png)

ArrayBlockingQueue блокируется только на LSMCustomSession, в то время как LinkedBlockingQueue имеет блокировки ещё и в 
очереди. Это объясняет бОльшие задержки - скорее всего переполняется очередь. Virtual Threads блокируются только на сессии.

## 1 thread, 64 connections

