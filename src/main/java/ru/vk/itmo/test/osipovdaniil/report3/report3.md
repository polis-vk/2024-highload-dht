# Отчёт по 3 стэйджу

## wrk

Минорная разница по сравнению со 2 стейджем в небоьшом увеличении времени ответа
в связи с выбором кластера.

## asyncprof

С точки зрения cpu разницы нет.
Например: [cpu1](../report2/asyncprof/prof_128_cpu_put.html)
и [cpu2](asyncprof/prof_get_cpu_128.html)

С точки зрения аллокаций добавляется аллокации на `handleProxyRequest` 
отвечающий за обработку запроса другим кластером, а также на `getTargetUrl`
[alloc](asyncprof/prof_get_alloc_128.html)

В случае с lock'ами, тзменилось только их распределение, так `SelectorThread` стал отжирать больше, а lock на саму
таску меньше.
[lock1](../report2/asyncprof/prof_128_lock_put.html)
и [lock2](asyncprof/prof_get_lock_128.html)
