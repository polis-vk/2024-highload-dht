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
Перед ресерчем практическим путем опредилил, что наиболее стабильные результаты wrk наблюдаются на 8 потоках.
Также скрипты луа были переписаны на основе референса, чтобы обеспечить более правдоподобную нагрузку при параллельном wrk (используем рандом).

## PUT Research
Для начала определим точку насыщения для рандомно выбранного значения размера очереди 3072, linked blocking queue и при использовании всех потоков.
Зафиксируем эти значения и получим следующие результаты:
### 900 thds rps
```
 50.000%    1.02ms
 75.000%    1.61ms
 90.000%    4.01ms
 99.000%   17.45ms
 99.900%   34.59ms
 99.990%   47.23ms
 99.999%   53.41ms
100.000%   60.03ms
```
Выдерживаем без каких-либо проблем.
### 950 thds rps - точка насыщения
```
 50.000%   21.77ms
 75.000%  792.06ms
 90.000%    1.19s
 99.000%    1.72s
 99.900%    1.85s
 99.990%    1.90s
 99.999%    1.91s
100.000%    1.92s
```

ALLOC
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task2/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task2/asprof/put/alloc/png/h_p950000rps_24threads_3072linked_64connections.png)

CPU
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task2/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task2/asprof/put/cpu/png/h_p950000rps_24threads_3072linked_64connections.png)

LOCK
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task2/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task2/asprof/put/lock/png/h_p950000rps_24threads_3072linked_64connections.png)

Далее хотелось бы поэкспериментировать с типом и размером очереди. Рассмотрим array blocking queue и различные вариации init capacity.

### 950 thds rps, array vs linked
LINKED 1024
```
50.000%    1.53ms
75.000%    9.34ms
90.000%    1.12s
99.000%    2.87s
99.900%    3.21s
99.990%    3.31s
99.999%    3.33s
100.000%    3.34s
```
ARRAY 1024
```
50.000%    5.22s
75.000%   10.22s
90.000%   13.36s
99.000%   24.64s
99.900%   31.80s
99.990%   32.93s
99.999%   33.05s
100.000%   33.06s
```

LINKED 3072
```
 50.000%   21.77ms
 75.000%  792.06ms
 90.000%    1.19s
 99.000%    1.72s
 99.900%    1.85s
 99.990%    1.90s
 99.999%    1.91s
100.000%    1.92s
```
LINKED 6114
```
 50.000%    1.31ms
 75.000%    4.36ms
 90.000%   13.19ms
 99.000%   46.72ms
 99.900%   83.97ms
 99.990%  107.71ms
 99.999%  114.50ms
100.000%  118.65ms
```

LINKED 8192
```
 50.000%    1.19ms
 75.000%    3.05ms
 90.000%   10.28ms
 99.000%   36.96ms
 99.900%   60.64ms
 99.990%   77.25ms
 99.999%   83.07ms
100.000%   86.59ms
```
ARRAY 8192
```
 50.000%    2.01s
 75.000%    3.56s
 90.000%    5.57s
 99.000%   15.05s
 99.900%   18.73s
 99.990%   19.81s
 99.999%   19.91s
100.000%   19.92s
```

LINKED 10240
```
 50.000%    1.24ms
 75.000%    3.59ms
 90.000%   11.06ms
 99.000%   36.16ms
 99.900%   60.80ms
 99.990%   75.97ms
 99.999%   87.68ms
100.000%   92.99ms
```
ARRAY 12000
```
 50.000%    3.22s
 75.000%    6.13s
 90.000%    8.27s
 99.000%   20.81s
 99.900%   23.66s
 99.990%   25.21s
 99.999%   25.36s
100.000%   25.38s
```

LINKED 12288
```
 50.000%    1.34ms
 75.000%    4.97ms
 90.000%   14.76ms
 99.000%   49.41ms
 99.900%   87.61ms
 99.990%  126.85ms
 99.999%  136.57ms
100.000%  141.05ms
```
ARRAY 24000
```
 50.000%    3.87s
 75.000%    7.30s
 90.000%   11.94s
 99.000%   22.48s
 99.900%   27.53s
 99.990%   28.41s
 99.999%   28.52s
100.000%   28.54s
```

Даже после прогрева array blocking queue дает результаты на порядок хуже, чем linked. Лучшими вариантами из рассмотренных оказались:
Для array  - 12000
Для linked - 8192
Их уменьшение или увеличение приводит к деградации.
В целом, довольно ожидаемое поведение. При большой очереди копятся старые запросы при большой нагрузке, а
при маленькой можем не успевать обрабатывать.

LINKED 1024
ALLOC
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task2/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task2/asprof/put/alloc/png/h_p950000rps_24threads_1024linked_64connections.png)

CPU
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task2/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task2/asprof/put/cpu/png/h_p950000rps_24threads_1024linked_64connections.png)

LOCK
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task2/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task2/asprof/put/lock/png/h_p950000rps_24threads_1024linked_64connections.png)

ARRAY 1024
ALLOC
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task2/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task2/asprof/put/alloc/png/h_p950000rps_24threads_1024array_64connections.png)

CPU
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task2/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task2/asprof/put/cpu/png/h_p950000rps_24threads_1024array_64connections.png)

LOCK
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task2/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task2/asprof/put/lock/png/h_p950000rps_24threads_1024array_64connections.png)

LINKED 8192
ALLOC
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task2/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task2/asprof/put/alloc/png/h_p950000rps_24threads_8192linked_64connections.png)

CPU
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task2/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task2/asprof/put/cpu/png/h_p950000rps_24threads_8192linked_64connections.png)

LOCK
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task2/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task2/asprof/put/lock/png/h_p950000rps_24threads_8192linked_64connections.png)

ARRAY 8192
ALLOC
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task2/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task2/asprof/put/alloc/png/h_p950000rps_24threads_8192array_64connections.png)

CPU
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task2/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task2/asprof/put/cpu/png/h_p950000rps_24threads_8192array_64connections.png)

LOCK
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task2/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task2/asprof/put/lock/png/h_p950000rps_24threads_8192array_64connections.png)

LINKED 12288
ALLOC
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task2/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task2/asprof/put/alloc/png/h_p950000rps_24threads_12288linked_64connections.png)

CPU
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task2/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task2/asprof/put/cpu/png/h_p950000rps_24threads_12288linked_64connections.png)

LOCK
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task2/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task2/asprof/put/lock/png/h_p950000rps_24threads_12288linked_64connections.png)

ARRAY 24000
ALLOC
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task2/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task2/asprof/put/alloc/png/h_p950000rps_24threads_24000array_64connections.png)

CPU
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task2/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task2/asprof/put/cpu/png/h_p950000rps_24threads_24000array_64connections.png)

LOCK
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task2/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task2/asprof/put/lock/png/h_p950000rps_24threads_24000array_64connections.png)


Далее поиграемся с потоками
### 950 thds rps, threads diff
Используем размер linked с размером 8192.

24 THREADS
```
 50.000%    1.19ms
 75.000%    3.05ms
 90.000%   10.28ms
 99.000%   36.96ms
 99.900%   60.64ms
 99.990%   77.25ms
 99.999%   83.07ms
100.000%   86.59ms
```
16 THREADS
Не наблюдаем сильной деградации, в целом ожидаемо.
```
 50.000%    1.42ms
 75.000%    6.32ms
 90.000%   17.97ms
 99.000%   55.68ms
 99.900%   88.77ms
 99.990%  120.89ms
 99.999%  130.75ms
100.000%  132.99ms
```
8 THREADS
Все еще терпимо, хотя результаты и хуже.
```
 50.000%    1.49ms
 75.000%    8.02ms
 90.000%   26.14ms
 99.000%   84.93ms
 99.900%  157.31ms
 99.990%  183.81ms
 99.999%  197.12ms
100.000%  202.62ms
```
ALLOC
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task2/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task2/asprof/put/alloc/png/h_p950000rps_8threads_8192linked_64connections.png)

CPU
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task2/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task2/asprof/put/cpu/png/h_p950000rps_8threads_8192linked_64connections.png)

LOCK
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task2/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task2/asprof/put/lock/png/h_p950000rps_8threads_8192linked_64connections.png)

4 THREADS
Вот здесь довольно сомнительные результаты. 8 производительных ядер на интеле, в сравнении с 8 тредами, ожидал сильную просадку,
а оказалось лучше, чем на 8 потоках. (Был прогрев, запускал пару раз)
```
 50.000%    1.40ms
 75.000%    5.84ms
 90.000%   19.57ms
 99.000%   59.26ms
 99.900%   94.01ms
 99.990%  118.08ms
 99.999%  133.76ms
100.000%  139.01ms
```
ALLOC
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task2/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task2/asprof/put/alloc/png/h_p950000rps_4threads_8192linked_64connections.png)

CPU
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task2/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task2/asprof/put/cpu/png/h_p950000rps_4threads_8192linked_64connections.png)

LOCK
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task2/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task2/asprof/put/lock/png/h_p950000rps_4threads_8192linked_64connections.png)

2 THREADS
Вполне ожидаемый результат.
```
 50.000%   11.85s
 75.000%   16.08s
 90.000%   18.53s
 99.000%   19.99s
 99.900%   20.19s
 99.990%   20.27s
 99.999%   20.32s
100.000%   20.33s
```
ALLOC
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task2/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task2/asprof/put/alloc/png/h_p950000rps_2threads_8192linked_64connections.png)

CPU
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task2/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task2/asprof/put/cpu/png/h_p950000rps_2threads_8192linked_64connections.png)

LOCK
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task2/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task2/asprof/put/lock/png/h_p950000rps_2threads_8192linked_64connections.png)

1 THREAD
Примерно те же значения, что были без использования пула потоков.
```
 50.000%   33.16s
 75.000%   47.15s
 90.000%    0.93m
 99.000%    1.01m
 99.900%    1.02m
 99.990%    1.02m
 99.999%    1.02m
100.000%    1.02m
```

Остановился бы на использовании 24 потоков, с использованием LinkedBlockingQueue с размером очереди 8192.
По сравнению с первой реализацией стало намного лучше, стали выдерживать почти в 10 раз больше rps.

## GET Research
Для начала определим точку насыщения при использовании эффективной для put трафика реализации. linked blocking queue, 8192 очередь и при использовании всех потоков.
Зафиксируем эти значения и получим следующие результаты:
### 24 thds rps
```
 50.000%    2.29ms
 75.000%    3.17ms
 90.000%    4.49ms
 99.000%    9.85ms
 99.900%   21.15ms
 99.990%   33.82ms
 99.999%   43.74ms
100.000%   49.02ms
```
Выдерживаем без каких-либо проблем.
### 25 thds rps - точка насыщения
```
 50.000%    4.45s
 75.000%    6.30s
 90.000%    6.83s
 99.000%    7.48s
 99.900%    7.67s
 99.990%    7.79s
 99.999%    7.82s
100.000%    7.83s
```

ALLOC
![get](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task2/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task2/asprof/get/alloc/png/h_25_000rps_24threads_8192linked_64connections.png)

CPU
![get](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task2/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task2/asprof/get/cpu/png/h_25_000rps_24threads_8192linked_64connections.png)

LOCK
![get](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task2/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task2/asprof/get/lock/png/h_25_000rps_24threads_8192linked_64connections.png)

Далее снова хотелось бы поэкспериментировать с типом и размером очереди. Рассмотрим array blocking queue и различные вариации init capacity.

### 25 thds rps, array vs linked
LINKED 8192
```
 50.000%    4.45s
 75.000%    6.30s
 90.000%    6.83s
 99.000%    7.48s
 99.900%    7.67s
 99.990%    7.79s
 99.999%    7.82s
100.000%    7.83s
```
ARRAY 12000
```
 50.000%    3.08ms
 75.000%  128.51ms
 90.000%  485.63ms
 99.000%  701.95ms
 99.900%  794.62ms
 99.990%  910.85ms
 99.999%  923.14ms
100.000%  928.77ms
```

LINKED 12000
```
 50.000%   32.30ms
 75.000%  153.73ms
 90.000%  312.06ms
 99.000%  670.21ms
 99.900%  889.34ms
 99.990%  938.49ms
 99.999%  950.78ms
100.000%  954.37ms
```
LINKED 24000
```
 50.000%    2.17ms
 75.000%    2.96ms
 90.000%    4.09ms
 99.000%   14.06ms
 99.900%   48.48ms
 99.990%   80.64ms
 99.999%   91.01ms
100.000%   96.96ms
```

Неожиданно, но array blocking queue стал показывать себя намного лучше на get запросах. А также, с ростом размера очереди
теперь не наблюдается деградации. Кажется, Linked по прежнему справляется с задачей лучше. Видимо, имеет более эффективный подход к
управлению ресурсами при параллельной обработке.
Также, решил проверить размер очереди как MAX_INT. Получились интересные результаты для 26 тысяч запросов:
LINKED MAX_INT
```
 50.000%    3.60ms
 75.000%    6.27ms
 90.000%   12.73ms
 99.000%   81.60ms
 99.900%  133.89ms
 99.990%  176.13ms
 99.999%  200.57ms
100.000%  208.13ms
```
ARRAY MAX_INT
```
 50.000%    3.38ms
 75.000%    7.18ms
 90.000%   37.63ms
 99.000%  164.35ms
 99.900%  248.57ms
 99.990%  290.05ms
 99.999%  304.38ms
100.000%  309.25ms
```
Мы смогли обработать на 1к rps больше, чем при подобранном ранее размере 8192 или 12к. Для них это значения было не подъемным.
Появилось еще одно предположение - jit смог оптимизировать размер очереди и использовал наиболее подходящую. Это подтверждается тем, что
запуски без прогрева имеют совсем печальные результаты.
Из плюсов - не нужно искать оптимальную очередь. Из минусов - не сразу имеем максимальную производтельность. Интересно, сможет ли он
деоптимизировать значение, если нагрузка изменится. Но уже не стал проверять, времени мало.

LINKED MAX_INT
ALLOC
![get](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task2/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task2/asprof/get/alloc/png/h_g26000rps_24threads_MAX_INT_linked_64connections.png)

CPU
![get](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task2/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task2/asprof/get/cpu/png/h_g26000rps_24threads_MAX_INT_linked_64connections.png)

LOCK
![get](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task2/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task2/asprof/get/lock/png/h_g26000rps_24threads_MAX_INT_linked_64connections.png)

ARRAY MAX_INT
ALLOC
![get](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task2/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task2/asprof/get/alloc/png/h_g26000rps_24threads_MAX_INT_array_64connections.png)

CPU
![get](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task2/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task2/asprof/get/cpu/png/h_g26000rps_24threads_MAX_INT_array_64connections.png)

LOCK
![get](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task2/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task2/asprof/get/lock/png/h_g26000rps_24threads_MAX_INT_array_64connections.png)

Далее поиграемся с потоками.

### threads diff
1 thread - точка насыщения 4 thds
2 thread - точка насыщения 4,5 thds
4 thread - точка насыщения 6,5 thds
8 thread - точка насыщения 9 thds
16 thread - точка насыщения 21 thds
24 thread - точка насыщения 25 (26 if MAX_INT) thds

Интересно, что улучшение неравномерное, самый большой скачок в производительности 8 -> 16 потоков.

8 THREADS
ALLOC
![get](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task2/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task2/asprof/get/alloc/png/h_g12000rps_8threads_8192linked_64connections.png)

CPU
![get](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task2/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task2/asprof/get/cpu/png/h_g12000rps_8threads_8192linked_64connections.png)

LOCK
![get](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task2/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task2/asprof/get/lock/png/h_g12000rps_8threads_8192linked_64connections.png)

16 THREADS
ALLOC
![get](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task2/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task2/asprof/get/alloc/png/h_g20000rps_16threads_8192linked_64connections.png)

CPU
![get](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task2/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task2/asprof/get/cpu/png/h_g20000rps_16threads_8192linked_64connections.png)

LOCK
![get](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task2/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task2/asprof/get/lock/png/h_g20000rps_16threads_8192linked_64connections.png)

Остановился бы на использовании 24 потоков, с использованием LinkedBlockingQueue с размером очереди 24000. Однако, очевидно, что
есть лучшее значение, которое пощволит выдержать 26 thds rps.
