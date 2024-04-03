## Гипотезы

Т.к. на нашу ноду теперь приходится всего 1/n ключей

То время бинарного поиска значительно сократится, не только по количеству запросов,
но и по их сложности, т.к. данных на ноде станет меньше

Однако, при этом значительную нагрузку будет создавать сеть и простой на ней

## Факты

Напоминаю, что при размере кластера 1 у нас получились следующие замеры
* PUT 48000:
  [alloc](html%2Fstage3%2Fcluster_1_put_alloc.html)
  [cpu](html%2Fstage3%2Fcluster_1_put_cpu.html)
  [lock](html%2Fstage3%2Fcluster_1_put_lock.html)
  [rps](html%2Fstage3%2Fcluster_1_put_wrk.txt)

* GET 60000:
[alloc](html%2Fstage3%2Fcluster_1_get_alloc.html)
[cpu](html%2Fstage3%2Fcluster_1_get_cpu.html)
[lock](html%2Fstage3%2Fcluster_1_get_lock.html)
[rps](html%2Fstage3%2Fcluster_1_get_wrk.txt)

*Повторная нагрузка "нового" кода показала, что значительного оверхеда от проверок нет*

Также мы будем профилировать только одну ноду кластера, чтобы видеть чистую нагрузку без примесей от других нод, поэтому она будет запущена в отдельном потоке

При этом, наверное, нужно было бы увеличить ограничение по памяти в зависимости от размера кластера до `128 * n` (где `n` - это количество нод).
Однако, было решено просто уменьшить порог flush'а, чтобы все было ок

## Посмотрим, что меняется в зависимости от размера кластера

*Т.к. rps снизился, то у нас меньше данных, следовательно в GET запросах больше 404, но мне не кажется это критичным из-за отсутсвия оптимизаций для отсутствующих записей.*


Нащупали RPS для 4 нод и от этого исходили на остальных:

### 2 ноды

#### PUT

Остановились на 36000, 5min: 
[alloc](html/stage3/cluster_2_stable_put_36000_alloc.html)
 [cpu](html/stage3/cluster_2_stable_put_36000_cpu.html)
 [lock](html/stage3/cluster_2_stable_put_36000_lock.html)
 [rps](html/stage3/cluster_2_stable_put_36000_wrk.txt)


#### GET
Остановились на 36000, 2min: 
 [alloc](html/stage3/cluster_2_get_stable_40000_alloc.html)
 [cpu](html/stage3/cluster_2_get_stable_40000_cpu.html)
 [lock](html/stage3/cluster_2_get_stable_40000_lock.html)
 [rps](html/stage3/cluster_2_get_stable_40000_wrk.txt)
 
### 4 ноды

#### PUT

Нащупали RPS: 
[35000](html/stage3/cluster_4_put_35000_wrk.txt)
[36000](html/stage3/cluster_4_put_36000_wrk.txt)
[37000](html/stage3/cluster_4_put_37000_wrk.txt)
[38000](html/stage3/cluster_4_put_38000_wrk.txt)
[48000](html/stage3/cluster_4_put_48000_wrk.txt)

Остановились на 36000, 5min: [alloc](html/stage3/cluster_4_stable_put_36000_alloc.html)
[cpu](html/stage3/cluster_4_stable_put_36000_cpu.html)
[lock](html/stage3/cluster_4_stable_put_36000_lock.html)
[rps](html/stage3/cluster_4_stable_put_36000_wrk.txt)

#### GET
Нащупали RPS: 
 [35000](html/stage3/cluster_4_get_35000_wrk.txt)
 [36000](html/stage3/cluster_4_get_36000_wrk.txt)
 [38000](html/stage3/cluster_4_get_38000_wrk.txt)
 [40000](html/stage3/
 cluster_4_get_40000_wrk.txt)
 [80000](html/stage3/cluster_4_get_80000_wrk.txt)

Остановились на 38000, 5min: 
[alloc](html/stage3/cluster_4_stable_get_38000_alloc.html)
[cpu](html/stage3/cluster_4_stable_get_38000_cpu.html)
[lock](html/stage3/cluster_4_stable_get_38000_lock.html)
[rps](html/stage3/cluster_4_stable_get_38000_wrk.txt) 

### 16 нод

#### PUT
Остановились на 30000, 5min:
[alloc](html/stage3/cluster_16_stable_put_30000_alloc.html)
[cpu](html/stage3/cluster_16_stable_put_30000_cpu.html)
[lock](html/stage3/cluster_16_stable_put_30000_lock.html)
[rps](html/stage3/cluster_16_stable_put_30000_wrk.txt)

#### GET

Остановились на 34000, 5min:
 [alloc](html/stage3/cluster_16_stable_get_34000_alloc.html)
 [cpu](html/stage3/cluster_16_stable_get_34000_cpu.html)
 [lock](html/stage3/cluster_16_stable_get_34000_lock.html)
 [rps](html/stage3/cluster_16_stable_get_34000_wrk.txt)

### Вывод
Легко заметить, что огромное количество процессорного времени уходит на `HttpClient`.

При этом плюсы от распределенности наших ключей и большего количества активных процессов не могут перевесить такое понижение производительности.

Самое интересно - это то, что вне зависимости от того какое количество нод было запущено. RPS упал примерно к равным показателям

Что вполне может говорить о бутылочном горлышке в виде ждущих `HttpClient`'ов хоть на локах их видно и не значительно.

Они занимают потоки, заставляя те ждать, не девая исполнить запросы адресованные этой ноде.

Как я понимаю, в следующих заданиях мы будем решать эту проблему при помощи асинхронных запросов

### Проверка алгоритма балансировки

В качестве алгоритма распределения ключей между серверами был выбран `consistent hashing`

Эмпирические наблюдения показывают, что данные распределеяются между нодами равномерно
![stage3_balancer.png](imgs%2Fstage3_balancer.png)