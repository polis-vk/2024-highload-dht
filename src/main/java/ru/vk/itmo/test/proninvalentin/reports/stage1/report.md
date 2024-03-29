## PUT 10K RPS без прогрева сервера:

Первым делом определим точку разладки нашего сервера.
В качестве запросов будем слать GET, запросы идут в пустую бд с только что запущенным сервером.

### Перцентили:
```
 50.000%  664.00us
 75.000%    0.95ms
 90.000%    1.13ms
 99.000%    2.00ms
 99.900%   10.98ms
 99.990%   24.13ms
 99.999%   25.09ms
100.000%   25.25ms
```

Видно, что время обработки запроса далеко от критичного, хотя тут конечно стоит смотреть на конкретный SLA у сервиса

### CPU Flame Graph 
![cpu-put-10000.png](aprof%2Fput%2Fcpu%2Fcpu-put-10000.png)

На графике видно, что мы тратим на поллинг запросов 57% всего времени, думаю это из-за недостаточного количества самих запросов)
Обработка запроса занимает 31%

На чтение из сокета уходит 16% от работы всего приложения 

3% на вставку в lsm и 9% на отправку ответа, думаю это не в последнюю очередь из-за скорости работы диска

8% ушло на работу GC, компиляцию и прочие необходимые процессы, т.к. сервер не прогрет

### ALLOC Flame Graph

![alloc-put-10000.png](aprof%2Fput%2Falloc%2Falloc-put-10000.png)

51% занимает парсинг тела запроса, если нырнуть в код one nio, то можно увидеть, что мы копируем тело запроса, аллоцируя память под массив байтов для него

Часть памяти уходит на то, чтобы аллоцировать пустой Response со статусом Accepted. 

Подумал, что можно завести константное поле для него, но видно это будет уже на 30K RPS

Также память выделяется в основном на конвертацию в MemorySegment

## PUT 10K RPS с прогревом сервера:

Сейчас в бд уже присутствует 1.5 гб данных + сервер не перезапускался после предыдущих замеров

В процессе работы я заметил, что при вставке записей с одинаковым ключом, размер папки с данными не уменьшается.

Причиной того является то, что в референсной реализации DAO не используется процесс compact 
(сама реализация написана, но нигде нету ее вызова. 

Думаю необходимо добавить триггер при флаше на проверку размера директории и в случае превышения определенного порога, начинать процесс сжатия)

По перцентилям ничего особо не поменялось - сервер продолжает справляться с нагрузкой, правда чуть шустрее, но я не думаю, что это из прогрева, т.к. при повторных запусках иногда сервер с прогревом справляется и чуть хуже.

### Перцентили:
```
 50.000%  659.00us
 75.000%    0.95ms
 90.000%    1.12ms
 99.000%    1.72ms
 99.900%    9.99ms
 99.990%   14.73ms
 99.999%   16.46ms
100.000%   16.77ms
```

### CPU Flame Graph

![cpu-put-heated-10000.png](aprof%2Fput%2Fcpu%2Fcpu-put-heated-10000.png)

На удивление, ничего толком и не поменялось, возможно, дело в том, что у меня выставлен трешхолд для флаша в 128 мб и вся работа происходит в памяти.

По аллокациям тоже ничего не поменялось

## PUT 20K RPS:

### Перцентили:
```
 50.000%    1.10ms
 75.000%    1.76ms
 90.000%    2.32ms
 99.000%    4.15ms
 99.900%    9.66ms
 99.990%   28.99ms
 99.999%   30.22ms
100.000%   30.35ms
```

Абсолютно идентичная ситуация, что и раньше, единственное, что время ответа увеличилось в два раза в редких ситуациях

## PUT 30K RPS:

Здесь и далее размер вставляемых элементов уменьшен в 10 раз, чтобы не переполнить диск

### Перцентили:
```
 50.000%  628.00us
 75.000%    0.93ms
 90.000%    1.11ms
 99.000%    8.65ms
 99.900%   35.13ms
 99.990%   62.40ms
 99.999%   65.09ms
100.000%   65.38ms
```

Видно, что серверу немного плохеет - подходим к точке разладки

### CPU Flame Graph

![cpu-put-30000.png](aprof%2Fput%2Fcpu%2Fcpu-put-30000.png)

По CPU все ровно также, однако поллинг занимает в два раза меньше общего времени - 27%.
Предполагаю, что теперь сервер не успевает также быстро обрабатывать запросы как и раньше

При добавлении констант для отправки ответа (ранее я заметил, что можно вместо создания одинаковых объектов для возвращения ответа с пустым телом сохранить их в память и каждый раз отдавать один и тот же объект) wrk почему-то начинает сбрасывать соединения с нашим сервером из-за чего появляется большое количество ошибок.

Поэтому от мемоизации ответов было решено отказаться

### Alloc Flame Graph

![alloc-put-30000.png](aprof%2Fput%2Falloc%2Falloc-put-30000.png)

## PUT 45K RPS:

Заметна точка разладки

### Перцентили:
```
 50.000%  926.21ms
 75.000%    1.61s
 90.000%    1.76s
 99.000%    1.83s
 99.900%    1.83s
 99.990%    1.83s
 99.999%    1.83s
100.000%    1.83s
```

Можно сказать, что каждый пользователь будет получать ответ за 2 секунды

### CPU Flame Graph

![cpu-put-45000.png](aprof%2Fput%2Fcpu%2Fcpu-put-45000.png)

### Alloc Flame Graph

![alloc-put-45000.png](aprof%2Fput%2Falloc%2Falloc-put-45000.png)

При этом графики особо ничем не отличаются от предыдущего пункта

## GET 20K RPS 3GB DB:

### Перцентили:
```
 50.000%  616.00us
 75.000%    0.91ms
 90.000%    1.08ms
 99.000%    2.07ms
 99.900%   25.47ms
 99.990%   44.26ms
 99.999%   45.60ms
100.000%   45.73ms
```

### CPU Flame Graph

![cpu-get-20000.png](aprof%2Fget%2Fcpu%2Fcpu-get-20000.png)

Ситуация идентична PUT запросам:
* Обработка запроса (62%)
* Поллинг (16%)
* Запись в сокет (12%)
* Чтение из сокета (6%)

Однако теперь основные затраты уходят на обработку самого запроса, связываю это со случайным доступом, тк в Lua скрипте я указываю случайный идентификатор записи.

### Alloc Flame Graph

![alloc-get-20000.png](aprof%2Fget%2Falloc%2Falloc-get-20000.png)

Ситуация изменилась, т.к. теперь мы не читаем и пишем в бд, возвращая response с пустым телом, а читаем из бд, выделяя память в основном под ответ и перевод MemorySegment'a в byte array.  
## GET 25K RPS 3GB DB:

### Перцентили:
```
 50.000%    0.92ms
 75.000%  472.83ms
 90.000%    2.46s
 99.000%    4.50s
 99.900%    4.64s
 99.990%    4.65s
 99.999%    4.65s
100.000%    4.65s
```
### CPU Flame Graph

![cpu-get-25000.png](aprof%2Fget%2Fcpu%2Fcpu-get-25000.png)

### Alloc Flame Graph

![alloc-get-25000.png](aprof%2Fget%2Falloc%2Falloc-get-25000.png)

Ситуация идентичная, однако по перцентилям видна деградация работы сервиса

# Возможные оптимизации

* Включить compaction, чтобы, ускорить поиск и уменьшить объем занимаемой памяти
* Для поиска можно прикрутить фильтр блума, что в совокупности с большим трешхолдом может значительно ускорить поиск
* Хорошо бы иметь возможность получить id ввиде набор байт, чтобы не тратить память на перевод строки
* Также имеет смысл рассмотреть добавление кеша, если мы знаем, что обладаем горячими ключами