# Асинхронное взаимодействие

При добавлении асинхронного взаимодействия на рассылку по сети, мы ожидаем, что показатели нашей системы улучшатся (повысится rps и уменьшится latency). 
Так как теперь мы вместо того, чтобы ждать пока все запросы к другим нодам не будут последовательно обработаны, мы:
- Отправляем запросы параллельно
- Отправляем ответ клиенту со скоростью ответов от ack самых быстрых нод (ранее, мы ждали обработку всех from запросов)

## Нагрузочное тестирование

### PUT

Точка разладки: ≈7000rps

60s
```
wrk -d 60 -t 4 -c 64 -R 7000 -L -s /Users/dariasupriadkina/IdeaProjects/2024-highload-dht/src/main/java/ru/vk/itmo/test/dariasupriadkina/scripts/upsert.lua http://localhost:8080
Running 1m test @ http://localhost:8080
  Thread Stats   Avg      Stdev     Max   +/- Stdev
    Latency    33.10ms   47.13ms 201.22ms   80.92%
    Req/Sec     1.75k   593.61     3.05k    62.83%
  Latency Distribution (HdrHistogram - Recorded Latency)
 50.000%    2.11ms
 75.000%   59.10ms
 90.000%  115.71ms
 99.000%  159.62ms
 99.900%  177.15ms
 99.990%  195.71ms
 99.999%  200.45ms
100.000%  201.34ms
```

30s
```
wrk -d 30 -t 4 -c 64 -R 7000 -L -s /Users/dariasupriadkina/IdeaProjects/2024-highload-dht/src/main/java/ru/vk/itmo/test/dariasupriadkina/scripts/upsert.lua http://localhost:8080
Running 30s test @ http://localhost:8080
  Thread Stats   Avg      Stdev     Max   +/- Stdev
    Latency    33.54ms   46.81ms 200.58ms   80.57%
    Req/Sec     1.76k   624.58     3.09k    61.76%
  Latency Distribution (HdrHistogram - Recorded Latency)
 50.000%    1.90ms
 75.000%   61.12ms
 90.000%  115.01ms
 99.000%  154.75ms
 99.900%  185.22ms
 99.990%  196.86ms
 99.999%  199.42ms
100.000%  200.70ms
```

### GET-random

Точка разладки: ≈10000rps 

60s
```
wrk -d 60 -t 4 -c 64 -R 10000 -L -s /Users/dariasupriadkina/IdeaProjects/2024-highload-dht/src/main/java/ru/vk/itmo/test/dariasupriadkina/scripts/getrand.lua http://localhost:8080
Running 1m test @ http://localhost:8080
  Thread Stats   Avg      Stdev     Max   +/- Stdev
    Latency    21.34ms   39.43ms 185.09ms   85.56%
    Req/Sec     2.50k   752.75     4.83k    78.54%
  Latency Distribution (HdrHistogram - Recorded Latency)
 50.000%    1.99ms
 75.000%   13.81ms
 90.000%   90.11ms
 99.000%  155.52ms
 99.900%  174.08ms
 99.990%  179.71ms
 99.999%  182.53ms
100.000%  185.22ms
```

30s
```
wrk -d 30 -t 4 -c 64 -R 10000 -L -s /Users/dariasupriadkina/IdeaProjects/2024-highload-dht/src/main/java/ru/vk/itmo/test/dariasupriadkina/scripts/getrand.lua http://localhost:8080
Running 30s test @ http://localhost:8080
  Thread Stats   Avg      Stdev     Max   +/- Stdev
    Latency    20.91ms   38.72ms 194.05ms   85.65%
    Req/Sec     2.51k   739.50     4.33k    78.71%
  Latency Distribution (HdrHistogram - Recorded Latency)
 50.000%    2.00ms
 75.000%   13.31ms
 90.000%   89.28ms
 99.000%  151.68ms
 99.900%  167.17ms
 99.990%  172.41ms
 99.999%  185.60ms
100.000%  194.18ms
```

### Результаты с прошлой лабораторной работы:

Так как в текущей лабораторной работе измерения делались с другими параметрами wrk (64 connections/4threads), 
было принято решение перемерить результаты, полученные в предыдущей лабораторной работе 

#### PUT

Точка разладки ≈4000rps
```
 wrk -d 30 -t 4 -c 64 -R 4000 -L -s /Users/dariasupriadkina/IdeaProjects/2024-highload-dht/src/main/java/ru/vk/itmo/test/dariasupriadkina/scripts/upsert.lua http://localhost:8080
Running 30s test @ http://localhost:8080
  Thread Stats   Avg      Stdev     Max   +/- Stdev
    Latency    27.73ms   40.23ms 176.26ms   81.04%
    Req/Sec     1.00k   238.36     1.65k    73.24%
  Latency Distribution (HdrHistogram - Recorded Latency)
 50.000%    1.85ms
 75.000%   49.31ms
 90.000%   99.78ms
 99.000%  134.01ms
 99.900%  162.30ms
 99.990%  174.08ms
 99.999%  176.25ms
100.000%  176.38ms

```

#### GET-random

Точка разладки ≈5000rps
```
dariasupriadkina@MacBook-Air async-profiler-3.0-macos % wrk -d 60 -t 4 -c 64 -R 5000 -L -s /Users/dariasupriadkina/IdeaProjects/2024-highload-dht/src/main/java/ru/vk/itmo/test/dariasupriadkina/scripts/getrand.lua http://localhost:8080
Running 1m test @ http://localhost:8080
  Thread Stats   Avg      Stdev     Max   +/- Stdev
    Latency    55.25ms   42.87ms 181.12ms   55.09%
    Req/Sec     1.25k   154.40     2.09k    81.95%
  Latency Distribution (HdrHistogram - Recorded Latency)
 50.000%   51.84ms
 75.000%   91.01ms
 90.000%  116.16ms
 99.000%  144.51ms
 99.900%  168.57ms
 99.990%  176.77ms
 99.999%  179.97ms
100.000%  181.25ms
```


### Сравнение
Latency очень близки друг к другу, однако, выдерживаемый rps увеличился почти в 2 раза, что на get, что на put запросах.

В прошлой лабораторной, большая часть %CPU уходила на метод handleRequest, в рамках которого 
последовательно осуществлялось взаимодействие по сети с другими нодами, сейчас, когда это взаимодействие происходит параллельно, 
можно было предполагать, что работа будет происходить быстрее. Сейчас выглядит так, будто из идеи асинхронного 
взаимодействия можно было вытянуть еще выгоды.

Повлиять на производительность могло несколько факторов:
- Неграмотная работа с пулами потоков (слишком мало или слишком много выделенных потоков под конкретную задачу)
- Свич контекст между потоками при выполнении коллбэков

Проведем профилирование и посмотрим, можем ли мы как-то добиться меньшей latency и большей производительности


## Профилироване

### PUT
Результаты профилирования PUT-запросов доступны по ссылкам:

[upsert-alloc.html](data%2Fupsert-alloc.html)

[upsert-cpu.html](data%2Fupsert-cpu.html)

[upsert-lock.html](data%2Fupsert-lock.html)

**Из интересного, при рассмотрении профиля CPU**, можно заметить, что теперь часть нашего пользовательского кода исполняется в `ForkJoinWorkerThread`. 
Это связано с работой коллбэков в `CompletableFuture`. Не сказать, что они там исполняются часто:

- apply(), вызванный коллбэком `thenApply()` в данном пуле потоков встречается в 0,35% сэмплов
- accept(), вызванный, вероятно, с помощью `.whenComplete()` в методе `collectResponsesCallback()` встречается в 0,70% сэмплов

По идее, хоть исполнение этих коллбэков в commonPool и не сыграло глобальной роли в распределении сэмплов, но сам факт того, что 
наш код исполняется в рамках разделяемого пула, который мы не можем контролировать, выглядит не очень правильным. 
Вероятно, следует рассмотреть возможность замены `whenComplete` на `whenCompleteAsync` и явно указать пул потоков, на котором мы хотим исполнять 
коллбэки. 

Также бросается в глаза, что в процентном соотношении метод `handleRequest` стал занимать 12% вместо 18%, как в прошлом этапе, снижение произошло как раз благодаря тому, что мы не ждем ответа всех нод, 
а отправляем все запросы параллельно и ждем только `ack` ответов (стоит отметить, что снижении было бы еще более значительным, если бы профиль в 4ой лабораторной был снят, 
когда я не экспериментировала с возможностью при получении ack ответов выходить из программы)  

**Профили аллокаций** выглядят похожим образом, на сравнении с профилями предыдущей лабораторной работой, однако, наличие коллбэков, исполняемых в `ForJoinWorkerThread`, добавило 
новых аллокаций в этом месте, которых раньше не было

Изменение метода `handleRequest` также немного повлияло на распределение аллокаций: изменилось с 23% до 30% 
(но здесь надо учитывать, что на эту цифру повлияло и добавление абсолютно новых аллокаций в виде аллокаций в 
`ForJoinWorkerThread`, о которых было сказано ранее)

**На профиле локов:**

В процентном соотношении `SelectorManager.run` и `PayloadThread.run` по-прежнему занимают 16% и 83% соответственно
В рамках работы `SelectorManager.run` заметно увеличилось время ожидания на локах для метода `ConnectionPool.purgeExpiredConnectionsAndReturnNextDeadline`: 
в прошлой реализации он занимал 0.93%, а теперь 9.36%

`ThreadPoolExecutor.getTask` уменьшилось с 8% до 3%, что может говорить о том, что на получение задач наши потоки стали тратить меньше времени, что 
может быть вызвано тем фактом, что у нас добавляется количество тасок, которые надо выполнить в виде коллбэков. Коллбэки сами по себе не слишком 
долго выполняются, однако могут занять для этого целый поток.

### GET
Результаты профилирования GET-запросов доступны по ссылкам:

[getrand-alloc.html](data%2Fgetrand-alloc.html)

[getrand-cpu.html](data%2Fgetrand-cpu.html)

[getrand-lock.html](data%2Fgetrand-lock.html)

С get-запросами все астоит аналогичным образом, что и с put, так как разницы в серверной обработке в моем решении 
нет абсолютно никакой, отличается лишь работа, выполняемая в dao. Отличается лишь степень изменения в %. Например, 
на профиле локов `ThreadPoolExecutor.getTask` уменьшилось с 11% до 2%, что говорит о том, что в очереди у нас почти всегда есть таски, 
которые необходимо обработать, благодаря чему, мы не блокируемся. 

Вообще, такое значение `ThreadPoolExecutor.getTask` и увеличение количества выполняемых тасок в поле, натолкнуло меня на мысль, 
что, возможно, имеет смысл увеличить количество потоков в `workerExecutor` и `nodeExecutor`. Раньше, увеличение количества потоков не приводило 
меня к каким-то серьезным положительным изменениям, но сейчас это выглядит весьма логично.

Я увеличила количество потоков в этих пулах в 2 раза: 
с 8 (по количеству ядер в машине, на которой выполняется лабораторная работа) до 16.

И это дало свои результаты. При повторном использовании wrk на get-запросах при 10000rps получилось следующее:

```
wrk -d 30 -t 4 -c 64 -R 10000 -L -s /Users/dariasupriadkina/IdeaProjects/2024-highload-dht/src/main/java/ru/vk/itmo/test/dariasupriadkina/scripts/getrand.lua http://localhost:8080
Running 30s test @ http://localhost:8080
  Thread Stats   Avg      Stdev     Max   +/- Stdev
    Latency     2.12ms    2.79ms  28.14ms   90.93%
    Req/Sec     2.64k   849.06     8.22k    85.14%
  Latency Distribution (HdrHistogram - Recorded Latency)
 50.000%    1.33ms
 75.000%    1.84ms
 90.000%    4.01ms
 99.000%   14.73ms
 99.900%   20.14ms
 99.990%   24.37ms
 99.999%   27.63ms
100.000%   28.16ms
```

Latency в разы уменьшилась

Чтобы обеспечить выполнение коллбэков в наших пулах, я явно указала, что хочу, чтобы эти коллбэки 
вызывались в `workerExecutor`'е за счет замены `.thenApply()` и `.whenComplete()` на `.thenApplyAsync()` и 
`.whenCompleteAsync()` с указанием `workerExecutor` в качестве параметра.

Сама производительность вроде как не изменилась, персентили не поменялись, а коллбэки из `ForkJoinWorkerThread`
исчезли: 

[getrand2-cpu.html](data%2Fgetrand2-cpu.html)

[getrand2-alloc.html](data%2Fgetrand2-alloc.html)

[getrand2-lock.html](data%2Fgetrand2-lock.html)