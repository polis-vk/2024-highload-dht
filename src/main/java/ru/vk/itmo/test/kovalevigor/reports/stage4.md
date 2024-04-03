## Гипотезы

В данной части задания мы добавили репликацию данных

Это обеспечение дополнительных гарантий для данных пользователя.
Так, в частности, теперь данные пользователя хранятся на нескольких репликах,
а для возврата какого-либо результата нам нужно его подтверждение от нескольких серверов.

Таким образом мы только замедлили наш сервис. Ожидается падение производительности
и нам интересно узнать насколько оно будет большим

## Факты

На лекции шла речь о тестировании 3 нод. Поэтому размер кластера в дальнейшем будет = 3

Также, в референсе куда-то пропало шардирование, либо я не понял код.
Однако, проводил нагрузку и с ним, сервер умирал сразу (D:)

По-прежнему используется `consistent hashing` и соответсвенно выбираются следующие ноды по кругу

## Напоминание

Старый код с шардированием работал в среднем на 36000 rps

**PUT**: [alloc](html%2Fstage4%2Fno_replica_put_alloc.html)
[cpu](html%2Fstage4%2Fno_replica_put_cpu.html)
[lock](html%2Fstage4%2Fno_replica_put_lock.html)
[wrk](html%2Fstage4%2Fno_replica_put_wrk.txt)


**GET**: [alloc](html%2Fstage4%2Fno_replica_get_alloc.html)
[cpu](html%2Fstage4%2Fno_replica_get_cpu.html)
[lock](html%2Fstage4%2Fno_replica_get_lock.html)
[wrk](html%2Fstage4%2Fno_replica_get_wrk.txt)

## Репликация

### PUT 

Перебираем rps
* [12000](html%2Fstage4%2Freplica_put_12000_wrk.txt)
* [15000](html%2Fstage4%2Freplica_put_15000_wrk.txt)
* [17000](html%2Fstage4%2Freplica_put_17000_wrk.txt)
* [17500](html%2Fstage4%2Freplica_put_17500_wrk.txt)
* [18000](html%2Fstage4%2Freplica_put_18000_wrk.txt)
* [36000](html%2Fstage4%2Freplica_put_36000_wrk.txt)

17500rps, 5 min: [alloc](html%2Fstage4%2Freplica_put_stable_alloc.html), [cpu](html%2Fstage4%2Freplica_put_stable_cpu.html), [lock](html%2Fstage4%2Freplica_put_stable_lock.html), [wrk](html%2Fstage4%2Freplica_put_stable_wrk.txt)

## Выводы

Как мы видим ничего сильно не поменялось, кроме RPS

Он стал значительно ниже из-за большего число обращений к соседним нодам.
Как мы видим локи висят на добавление в очередь, т.к. основная работа - это ожидание ответа от ноды соседа.
Из-за чего повисают все воркеры (локальные и удаленные запросы не разнесены по разным воркерам)
