## Параметры системы:
* Treshhold bytes = 1 mb
* Количество core потоков = 6
* Максимальное количество потоков = 6
* Размер очереди = 300
* Max heap size = 128 mb

## Исследуем заполнение БД

* ![](./screens/wrk-cmp/put_distribution.png)
    На приведенном рисунке видно, что БД действительно заполняется равномерно.

## Проведем сравнение производительности реализации с шардированием и без

* PUT
  * Сравнение ![](./screens/wrk-cmp/get_64_cmp.png)
  * Сравнение ![](./screens/wrk-cmp/get_250_cmp.png)
  * Сравнение ![](./screens/wrk-cmp/get_64_old_new.png)
  * Сравнение ![](./screens/wrk-cmp/get_250_old_new.png)

* GET
  * Сравнение ![](./screens/wrk-cmp/put_64_cmp.png)
  * Сравнение ![](./screens/wrk-cmp/put_250_cmp.png)
  * Сравнение ![](./screens/wrk-cmp/put_64_old_new.png)
  * Сравнение ![](./screens/wrk-cmp/put_250_old_new.png)

## Проведем профилирование

* PUT
  * ALLOC 
    ![](./screens/profiles/get_alloc.png)

  * CPU
    ![](./screens/profiles/get_cpu.png)
    ![](./screens/profiles/get_cpu_t.png)

  * LOCK
    ![](./screens/profiles/get_lock.png)
    ![](./screens/profiles/get_lock_t.png)
  
* GET
  * ALLOC
    ![](./screens/profiles/put_alloc_1.png)
    ![](./screens/profiles/put_alloc_2.png)

  * CPU
    ![](./screens/profiles/put_cpu.png)
    ![](./screens/profiles/put_cpu_t_1.png)
    ![](./screens/profiles/put_cpu_t_2.png)

  * LOCK
    ![](./screens/profiles/put_lock.png)
    ![](./screens/profiles/put_lock_t.png)