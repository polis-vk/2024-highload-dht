## Этап 1
При одновременном запуске wrk2 и async-profiler мой сервер выдерживает нагрузку в 6500 rps на GET запросы
и 22000 rps на PUT запросы \
(точка разладки - 8000 и 25000 rps соответственно).
Нагрузочное тестирование проводилось в 1 поток и 1 соединение

### Получились следующие результаты
#### Результаты работы wrk2:
[get_6500rps.txt](wrk%2Fresults%2Fstage1%2Fget_6500rps.txt) \
[put_22000rps.txt](wrk%2Fresults%2Fstage1%2Fput_22000rps.txt)

Попробуем увеличить ```flushThresholdBytes``` в 10 раз (1047552 -> 10475520)

[get_17000rps_flush_threshold_10x.txt](wrk%2Fresults%2Fstage1%2Fget_17000rps_flush_threshold_10x.txt) \
_Сервер выдерживает 17000 get запросов_ \
[get_20000rps_flush_threshold_10x.txt](wrk%2Fresults%2Fstage1%2Fget_20000rps_flush_threshold_10x.txt) \
_Сервер не выдерживает 20000 get запросов_

_Увеличение ```flushThresholdBytes``` уменьшило количество sstable'ов; мы стали тратить меньше ресурсов на чтение с диска_

#### Результаты работы async-profiler:
![get_alloc.png](asprof%2Fstage1%2Fget_alloc.png)
[get_alloc.png](asprof%2Fstage1%2Fget_alloc.png) \
_Видно, что аллокации распределены более-менее равномерно; вероятно, тут ничего оптимизировать не надо_

[get_cpu.png](asprof%2Fstage1%2Fget_cpu.png) \
![get_cpu.png](asprof%2Fstage1%2Fget_cpu.png)
_Здесь можно увидеть, что значительную часть процессорного времени (45%) занимает метод ```memorySegment.mismatch()```. \
Проанализировав свой код, я не нашел мест, где от вызова этого метода можно было бы избавиться_

[put_alloc.png](asprof%2Fstage1%2Fput_alloc.png) \
![put_alloc.png](asprof%2Fstage1%2Fput_alloc.png)
_Тут аллокации тоже почти равномерные, однако выделяется ```byte[]``` внутри one.nio read \
Я не думаю, что могу как-то уменьшить там число аллокаций_

[put_cpu.png](asprof%2Fstage1%2Fput_cpu.png) \
![put_cpu.png](asprof%2Fstage1%2Fput_cpu.png)
_Основная часть времени уходит на read, write и kevent из one.nio \
Возможно, можно сократить затраты процессорного времени, более тонко настроив сервер в one.nio_

## Этап 2
Нагрузочное тестирование будем проводить из четырех потоков в основном при 22000 rps на put и 6500 rps на get.
Проверим сначала, сколько соединений сможет выдержать сервер из первого этапа.

[t4_c12_R22000_put.txt](wrk%2Fresults%2Fstage2%2Fexecutor_threads_1%2Ft4_c12_R22000_put.txt) \
_При 12 соединениях сервер начинает ломаться_

[t4_c10_R22000_put.txt](wrk%2Fresults%2Fstage2%2Fexecutor_threads_1%2Ft4_c10_R22000_put.txt) \
_Сервер выдерживает нагрузку в 10 соединений_

Сделаем сервер асинхронным с помощью
```java
new ThreadPoolExecutor(
            1,
            8,
            1,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8)
    );
```

[t4_c20_R22000.txt](wrk%2Fresults%2Fstage2%2Ft4_c20_R22000.txt) \
_Сервер ломается при 20 соединениях_

Попробуем поменять ```coreSize``` на 8 

[t4_c20_R22000_core8.txt](wrk%2Fresults%2Fstage2%2Ft4_c20_R22000_core8.txt) \
_Такое изменение не дало результатов_

Попробуем вернуть ```coreSize``` на 1 и поменять ```maxSize``` на 16

[t4_c20_R22000_max16.txt](wrk%2Fresults%2Fstage2%2Ft4_c20_R22000_max16.txt) \
_Сервер продолжает падать_

Попробуем увеличить размер очереди:
```java
new ThreadPoolExecutor(
            4,
            8,
            1,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(80)
    );
```

[t4_c20_R65000.txt](wrk%2Fresults%2Fstage2%2Ft4_c20_R65000.txt) \
_Такая конфигурация выдерживает 60000 rps при 20 соединениях_

[t4_c170_R22000_cs_4_ms_8_qs_80.txt](wrk%2Fresults%2Fstage2%2Ft4_c170_R22000_cs_4_ms_8_qs_80.txt) \
_Или 22000 rps при 170 соединениях_

[t4_c20_R65000.txt](wrk%2Fresults%2Fstage2%2Ft4_c20_R65000.txt)
_Сервер не выдерживает 65000 rps при 20 соединениях_

_Асинхронный сервер выдерживает больше rps на put за счет снятия нагрузки с selector тредов_

[t4_c200_R22000_cs_4_ms_8_qs_80.txt](wrk%2Fresults%2Fstage2%2Ft4_c200_R22000_cs_4_ms_8_qs_80.txt) \
_Сервер ломается при 200 соединениях_

Попробуем еще увеличить размер очереди (до 200):

[t4_c650_R22000_qs200.txt](wrk%2Fresults%2Fstage2%2Ft4_c650_R22000_qs200.txt) \
_Сервер выдерживает 650 соединений_

[t4_c700_R22000_qs200.txt](wrk%2Fresults%2Fstage2%2Ft4_c700_R22000_qs200.txt) \
_Сервер ломается при 700 соединениях_

Сравним результаты профилирования cpu для очереди на 80 элементов и очереди на 200 элементов:

![put_cpu_qs80.png](asprof%2Fstage2%2Fput_cpu_qs80.png)
[put_cpu_qs80.png](asprof%2Fstage2%2Fput_cpu_qs80.png) \
![put_cpu_qs200.png](asprof%2Fstage2%2Fput_cpu_qs200.png)
[put_cpu_qs200.png](asprof%2Fstage2%2Fput_cpu_qs200.png) \
_Видно что при увеличении размера очереди возрастает доля KQUeue.poll_

![put_alloc_qs80.png](asprof%2Fstage2%2Fput_alloc_qs80.png)
[put_alloc_qs80.png](asprof%2Fstage2%2Fput_alloc_qs80.png) \
_По сравнению с прошлым этапом результаты особо не изменились_ \
![put_alloc_qs200.png](asprof%2Fstage2%2Fput_alloc_qs200.png)
[put_alloc_qs200.png](asprof%2Fstage2%2Fput_alloc_qs200.png) \
_То же самое что и с queueSize = 80_

![put_lock_qs80.png](asprof%2Fstage2%2Fput_lock_qs80.png)
[put_lock_qs80.png](asprof%2Fstage2%2Fput_lock_qs80.png) \
_Блокировки в основном приходятся на методы блокирующей очереди и 22.96% - на ```sendResponse``` из one.nio_
![put_lock_qs200.png](asprof%2Fstage2%2Fput_lock_qs200.png)
[put_lock_qs200.png](asprof%2Fstage2%2Fput_lock_qs200.png) \
_Результаты аналогичны за тем исключением, что блокировка на ```take``` преобладает над блокировкой на ```poll```_



Вывод:
Увеличение размера очереди увеличивает максимальное число соединений, которые способен держать сервер


Попробуем поменять ```ArrayBlockingQueue``` на ```LinkedBlockingDeque``` (размер очереди - 80)

[t4_c170_R22000_cs_4_ms_8_qs_80_linked_deq.txt](wrk%2Fresults%2Fstage2%2Ft4_c170_R22000_cs_4_ms_8_qs_80_linked_deq.txt) \
_Сервер начинает ломаться при 170 соединениях_

Попробуем теперь использовать ```Executors.newVirtualThreadPerTaskExecutor()```

[t4_c170_R22000_virtual_thread_per_task.txt](wrk%2Fresults%2Fstage2%2Ft4_c170_R22000_virtual_thread_per_task.txt) \
_Сервер выдерживает 170 соединений, но только 0.999316 отвечает на запросы приемлемое время (< 20 мс / запрос)_

Проверим get запросы на такой конфигурации ```Executor``` \
Тестирование get запросов будем проводить только на ней, так как только с такой конфигурацией (с точностью до размера очереди) put запросы отрабатывают приемлемо
```java
new ThreadPoolExecutor(
            4,
            8,
            1,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(80)
    );
```

[get_c170_qs80.txt](wrk%2Fresults%2Fstage2%2Fget_c170_qs80.txt) \
_Сервер не выдерживает 170 соединений_

[get_c150_qs80.txt](wrk%2Fresults%2Fstage2%2Fget_c150_qs80.txt) \
_Сервер выдерживает 150 соединений_

[get_t4_c20_R15000.txt](wrk%2Fresults%2Fstage2%2Fget_t4_c20_R15000.txt)
_Сервер выдерживает 15000 rps при 20 соединениях_

[get_t4_c20_R19000.txt](wrk%2Fresults%2Fstage2%2Fget_t4_c20_R19000.txt)
_Сервер не выдерживает 19000 rps при 20 соединениях_

_Асинхронный сервер выдерживает больше rps на get за счет снятия нагрузки с selector тредов_

![get_cpu_qs80.png](asprof%2Fstage2%2Fget_cpu_qs80.png)
[get_cpu_qs80.png](asprof%2Fstage2%2Fget_cpu_qs80.png) \
_Большая часть все так же тратится на поиск в файли (и на mismatch - в частности)_

![get_alloc_qs80.png](asprof%2Fstage2%2Fget_alloc_qs80.png)
[get_alloc_qs80.png](asprof%2Fstage2%2Fget_alloc_qs80.png) \
_Много аллокаций приходится на метод ```tryAcquireShared``` внутри поиска вхождения в файле; не думаю что я могу поменять тут что-то_

![get_lock_qs80.png](asprof%2Fstage2%2Fget_lock_qs80.png)
[get_lock_qs80.png](asprof%2Fstage2%2Fget_lock_qs80.png) \
_Большинство блокировок приходится на блокирующую очередь_

Попробуем увеличить ```flushThresholdBytes``` в 10 раз

![get_lock_qs80_ftb10x.png](asprof%2Fstage2%2Fget_lock_qs80_ftb10x.png)
[get_lock_qs80_ftb10x.png](asprof%2Fstage2%2Fget_lock_qs80_ftb10x.png) \
_Количество блокировок уменьшилось с 7094 до 6638_