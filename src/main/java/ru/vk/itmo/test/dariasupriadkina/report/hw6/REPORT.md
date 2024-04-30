# Range-запросы

На начало проведения экспериментов база была заполнена на 777Мб, количество записей - ≈8_990_000

Снимем профили аллокации, блокировок и cpu, нагрузив систему командой curl, 
с помощью которой запросим все записи базы данных, начиная с 1ой: 

```dtd
curl -v http://localhost:8080/v0/entities\?start=1
```

## Результаты профилирования

[range-alloc.html](..%2F..%2F..%2F..%2F..%2F..%2F..%2F..%2F..%2F..%2F..%2F..%2FDownloads%2Fasync-profiler-3.0-macos%2Frange-alloc.html)

[range-cpu.html](..%2F..%2F..%2F..%2F..%2F..%2F..%2F..%2F..%2F..%2F..%2F..%2FDownloads%2Fasync-profiler-3.0-macos%2Frange-cpu.html)

[range-lock.html](..%2F..%2F..%2F..%2F..%2F..%2F..%2F..%2F..%2F..%2F..%2F..%2FDownloads%2Fasync-profiler-3.0-macos%2Frange-lock.html)