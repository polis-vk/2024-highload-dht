# Отчет о тестировании
## Использованные методы
Все методы реализованы так же как и методы в stage1,
как именно они реализованы можно посмотреть либо в отчете
stage1.md или в папке lua

После прогрева выяснилось, что точка разладки происходит где-то
в районе 3000-3500, поэтому будем тестировать на 3000 rps

### PUT запросы
```
./wrk -d 120 -t 1 -c 64 -R 3000 -L -s /home/andrew/my-dht/2024-highload-dht/src/main/java/ru/vk/itmo/test/smirnovandrew/lua/put.lua http://localhost:8080
 ```
```dtd
./profiler.sh --fdtransfer -d 120 -e cpu -f put_cpu_stage3.html jps
```

![put3000.png](stage5%2Fput3000.png)
Здесь интересно, что latency начинает расти где-то около 99.9%

Анализ CPU:
![put_cpu.png](stage5%2Fput_cpu.png)
Наибольшая нагрузка приходится на ожидание свободных потоков

Также на асинхронное отправление запроса на другой шард


Анализ ALLOC:
![put_alloc.png](stage5%2Fput_alloc.png)
Большее количество памяти так же, как и в предыдущих частях
тратится на конвертацию MemorySegment в byte[] и обратно

Также тратится на расшифрофку headerов и на посылку Completable future


Анализ LOCK:
![put_lock.png](stage5%2Fput_lock.png)
Наибольная нагрузка тут приходится на ожидание таски из очереди

Также тратится на ожидание асинхронного запроса

### GET запросы
```
./wrk -d 60 -t 1 -c 64 -R 3000 -L -s /home/andrew/my-dht/2024-highload-dht/src/main/java/ru/vk/itmo/test/smirnovandrew/lua/get.lua http://localhost:8080
 ```
```dtd
./profiler.sh --fdtransfer -d 120 -e cpu -f get_cpu_stage3.html jps
```

![get3000.png](stage5%2Fget3000.png)
Здесь также latency серьезно растет около 99.9%

Анализ CPU:
![get_cpu.png](stage5%2Fget_cpu.png)
Наибольшая нагрузка приходится на ожидание свободных потоков

Также на асинхронное отправление запроса на другой шард

Анализ ALLOC:
![get_alloc.png](stage5%2Fget_alloc.png)
Большее количество памяти так же, как и в предыдущих частях
тратится на конвертацию MemorySegment в byte[] и обратно

Также тратится на расшифрофку headerов и на посылку Completable future



Анализ LOCK:
![get_lock.png](stage5%2Fget_lock.png)
Наибольная нагрузка тут приходится на ожидание таски из очереди

Также тратится на ожидание асинхронного запроса


### Сравнение с предыдущей реализацией
![compare.png](stage5%2Fcompare.png)
К сожалению я не делал графики на stage4, поэтому могу сравнить
показатели только со stage3. Здесь по графикам заметно, что относительно
stage3 есть значительные улучшения по latency


