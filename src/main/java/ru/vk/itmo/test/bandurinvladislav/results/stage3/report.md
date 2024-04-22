# Stage 3

После реализации партиционирования, для тестов нужно было заполнить базу. При запуске [скрипта](..%2F..%2Fscripts%2FPutRequest.lua)
командой `wrk -d 600 -t 8 -c 64 -R 30000 -L -s ./scripts/PutRequest.lua http://localhost:8080`, через минуту начал вылетать OutOfMemoryError.
Для вычисления проблемы я воспользовался _visualVM_. 

При мониторинге я выяснил, что очень много памяти занимают объекты _BaseEntry_, которые хранятся in-memory, пока их не сбросят на диск.
Так как теперь у нас не один кластер, а несколько, каждый из них ждёт, пока _ConcurrentSkipListMap_ не заполнится => heap раздувается и вылетает ошибка. 
![heapdump.png](profile_png%2Fheapdump.png)
Чтобы решить эту проблему, пришлось уменьшить `FLUSH_THRESHOLD_BYTES` до 3 МБ.

Чтобы объективно оценить изменения нового этапа с новым объёмом БД (~ 300 МБ), перемерю stage-2 и выявлю новую нагрузку, с которой сервер в состоянии справиться.
1) [put_R150k_stage2](profile_wrk%2Fput_R150k_stage2)
2) [get_110k_stage2](profile_wrk%2Fget_110k_stage2)

### Тестирование с шардированием

1) [get](profile_wrk%2Fget_110k) - сервер выдерживает ту же нагрузку, что и раньше.
![get_hist.png](profile_png%2Fget_hist.png)


2) [put](profile_wrk%2Fput_75k) - производительность упала в 2 раза, т.к. больше, чем `RPS=75_000` сервер не выдерживает.
![put_hist.png](profile_png%2Fput_hist.png)

#### Аллокации
[getAlloc.html](profile_html%2FgetAlloc.html) \
[putAlloc.html](profile_html%2FputAlloc.html) \
В сравнении с прошлыми этапами, большую долю аллокаций теперь составляет выделение _byte[]_ при создании _ResponseReader_.
![alloc.png](profile_png%2Falloc.png)

#### CPU

[getCpu.html](profile_html%2FgetCpu.html) \
[putCpu.html](profile_html%2FputCpu.html) \
Теперь помимо непосредственного поиска/вставки записи в базу данных, часть цпу отнимает делегирование запросов по сети на другие кластеры
![cpu.png](profile_png%2Fcpu.png)

#### lock

[getLock.html](profile_html%2FgetLock.html) - ситуация особо не изменилась относительно прошлого этапа. \
[putLock.html](profile_html%2FputLock.html) - увеличилась доля блокировок на метод _Session.process_ из-за хождений по сети.


### Тестирование равномерности распределения с разными хэш-функциями

Для сравнения распределений я выбрал murmur3, SHA-1 и MD5, результаты можно увидеть на картинках:

1) murmur3 \
![murmur3.png](profile_png%2Fmurmur3.png)

2) SHA-1 \
![Sha-1.png](profile_png%2FSha-1.png)

3) MD5 \
![MD5.png](profile_png%2FMD5.png)

Видно, что у этих функций распределение равномерное

#### Вывод
При добавлении новых кластеров, появилась дополнительная нагрузка в виде хождений по сети, из-за чего общая производительность
нашей системы упала.