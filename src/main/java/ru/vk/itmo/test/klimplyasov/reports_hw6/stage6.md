## Нагрузочное тестирование
### async-profiler
## Вывод профилирования аллокации
- `one/nio/net/Session.write` - 46%
  - writeDataChunk -70% 
  - AbstractMemorySegmentImpl.toArray - 24%
- LiveFilteringIterator.next - 29%

## Вывод профилирования CPU

- `Session.write` - 95%
- LiveFilteringIterator.next - 4%
- writeDataChunk -0.1%
