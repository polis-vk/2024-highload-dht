## Гипотезы

В данной части задания мы осуществили переход с `one.nio.HttpClient` на предоставляемый в стандартном пакете `HttpClient` в силу того,
что второй поддерживает асинхронное взаимодействие.

В связи с чем сначала сравним `one.nio` реализацию со стандартной,
а затем стандартную синхронную со стандартной асинхронной и сделаем выводы.

Ожидается просадка производительности в первом случае и какое-то повышение во втором.

## Факты

Размер кластера в дальнейшем будет = 3

По-прежнему используется `consistent hashing` и соответсвенно выбираются следующие ноды по кругу

**GET** запросы идут на базу заполненую на ~700MB, т.е. 45 таблиц по 15MB. 

## Прошлый этап

**PUT** 17000rps: [5](html/stage5/old_put_cpu.html) [5](html/stage5/old_put_wrk.txt)

**GET** 14000rps: [5](html/stage5/old_get_cpu.html) [5](html/stage5/old_get_wrk.txt)

## Новый этап

### `HttpCleint` с синхронным взаимодействием

*(хеш коммита: `df67db85dbc02878a2bf29d97662744f52a6cc61`)*

#### PUT

Перебираем rps:
* [9000](html%2Fstage5%2Fsync_put_9000_wrk.txt)
* [10000](html%2Fstage5%2Fsync_put_10000_wrk.txt)
* [11000](html%2Fstage5%2Fsync_put_11000_wrk.txt)
* [13500](html%2Fstage5%2Fsync_put_13500_wrk.txt)
* [17000](html%2Fstage5%2Fsync_put_17000_wrk.txt)

На [9000](html%2Fstage5%2Fsync_put_9000_stable_wrk.txt)
и [10000](html%2Fstage5%2Fsync_put_10000_stable_wrk.txt) 
начинает значительно проседать производительность при долгой нагрузке

Поэтому остановился на 8500rps

8500rps 5min: [alloc](html%2Fstage5%2Fsync_put_8500_stable_alloc.html)
[cpu](html%2Fstage5%2Fsync_put_8500_stable_cpu.html)
[lock](html%2Fstage5%2Fsync_put_8500_stable_lock.html)
[rps](html%2Fstage5%2Fsync_put_8500_stable_wrk.txt)

#### GET

Перебираем rps:
* [7000](html%2Fstage5%2Fsync_get_7000_wrk.txt)
* [8000](html%2Fstage5%2Fsync_get_8000_wrk.txt)
* [9000](html%2Fstage5%2Fsync_get_9000_wrk.txt)
* [10000](html%2Fstage5%2Fsync_get_10000_wrk.txt)
* [12000](html%2Fstage5%2Fsync_get_12000_wrk.txt)
* [14000](html%2Fstage5%2Fsync_get_14000_wrk.txt)

[9000](html%2Fstage5%2Fsync_get_9000_stable_wrk.txt) rps не держатся стабильно,
поэтому остановился на 8000

8000rps 5min: [alloc](html%2Fstage5%2Fsync_get_8000_stable_alloc.html)
  [cpu](html%2Fstage5%2Fsync_get_8000_stable_cpu.html)
  [lock](html%2Fstage5%2Fsync_get_8000_stable_lock.html)
  [rps](html%2Fstage5%2Fsync_get_8000_stable_wrk.txt)

#### Сравнение с `one.nio`

Как мы видим стандартный клиент значительно медленее библиотечного

Также это видно и по `cpu` относительно процентного соотношения работы DAO и непосредственно работы с сетью

В силу падения RPS локи по добалению в очередь отошли на второй план.

И основное время простое происходит при использовании общих ресурсов, заявлемых в стандартном `HttpClient'е`

В плане алокаций можно говорить о паритете двух сравнимаемых реализаций

### `HttpCleint` с асинхронным взаимодействием

При реализации поменялась немного структура кода,
а именно,в асинхронном случае запросы на валидность проверяются сразу.

Профили бывает полезно смотреть не только для анализа узкий мест,
но и обнаружить нежелательное поведение.

Так, начав [профилировать](html%2Fstage5%2Fasync_bug_put_8500_cpu.html), 
я обнаружил, что часть задач для `future` выполняется в `ForkJoinPool`.

Хотя мы пытались этого избежать, создав контролируемые нами пулы, как для `HttpExecutor'а`,
так и для первичной обработки запросов.

Однако, отдельный пул на производительность сильно не повлиял:
[8500](html%2Fstage5%2Fasync_bug_put_8500_wrk.txt)
[10000](html%2Fstage5%2Fasync_bug_put_10000_wrk.txt)
[14000](html%2Fstage5%2Fasync_bug_put_14000_wrk.txt)
[16000](html%2Fstage5%2Fasync_bug_put_16000_wrk.txt)
[18000](html%2Fstage5%2Fasync_bug_put_18000_wrk.txt)
#### PUT

Перебираем rps:
* [8500](html%2Fstage5%2Fasync_put_8500_wrk.txt)
* [12000](html%2Fstage5%2Fasync_put_12000_wrk.txt)
* [13000](html%2Fstage5%2Fasync_put_13000_wrk.txt)
* [14000](html%2Fstage5%2Fasync_put_14000_wrk.txt)
* [15000](html%2Fstage5%2Fasync_put_15000_wrk.txt)
* [16000](html%2Fstage5%2Fasync_put_16000_wrk.txt)
* [18000](html%2Fstage5%2Fasync_put_18000_wrk.txt)

Просадка при [14000](html%2Fstage5%2Fasync_put_14000_stable_wrk.txt)rps,
поэтому остановился на 12000

12000rps 5min:
[alloc](html%2Fstage5%2Fasync_put_12000_stable_alloc.html)
[cpu](html%2Fstage5%2Fasync_put_12000_stable_cpu.html)
[lock](html%2Fstage5%2Fasync_put_12000_stable_lock.html)
[rps](html%2Fstage5%2Fasync_put_12000_stable_wrk.txt)

#### GET

Перебираем rps:
* [8500](html%2Fstage5%2Fasync_get_8500_wrk.txt)
* [11000](html%2Fstage5%2Fasync_get_11000_wrk.txt)
* [12000](html%2Fstage5%2Fasync_get_12000_wrk.txt)
* [13000](html%2Fstage5%2Fasync_get_13000_wrk.txt)
* [14000](html%2Fstage5%2Fasync_get_14000_wrk.txt)
* [15000](html%2Fstage5%2Fasync_get_15000_wrk.txt)
* [18000](html%2Fstage5%2Fasync_get_18000_wrk.txt)

Остановился на 11000rps

11000rps 5min:
[alloc](html%2Fstage5%2Fasync_get_11000_stable_alloc.html)
[cpu](html%2Fstage5%2Fasync_get_11000_stable_cpu.html)
[lock](html%2Fstage5%2Fasync_get_11000_stable_lock.html)
[rps](html%2Fstage5%2Fasync_get_11000_stable_wrk.txt)

#### Сравнение с синхронной версией

Как мы видим асинхронная реализация действительно повысила производительность

Локи распределеись в соответствии с непосредственным выполнением запросов и возврата их в общий пул.
А наш код от них избавлен в части формирования ответа на запрос

Как мы видим непосредственное взаимодействие с DAO - это лишь малая часть задачи, т.к. сеть значительно дороже, дольше.
А учитывая репликацию, то количество работы с сетью значительно превышает обычную задачу.

Это в целом и наблюдается из графов. Аллокации демонстрируют тоже

## Вывод

Как мы видим, несмотря даже на асинхронное взаимодействие,
сеть остается бутылочным горлышком. Так, к примеру, на графах мы видим, что `GET` запросы значительно дольше
и сильнее загружают `CPU`, однако просадка `RPS` уже не так значительна как раньше
