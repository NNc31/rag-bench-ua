# rag-bench-ua: оцінювання конфігурацій конвеєра RAG на україномовному корпусі

Програмний стенд для експериментального вибору конфігурації конвеєра retrieval-augmented generation (RAG) у складі хмарної платформи управління даними.
Порівнюються моделі векторних подань, розміри фрагментів документів і глибина пошуку за метриками Hit@k, MRR, оцінкою правильності відповідей (модель-суддя) та затримкою.
Усі обчислення виконуються локально (Ollama + PostgreSQL/pgvector) - дані не залишають периметр.

## Вимоги

- JDK 17+ та Maven
- Docker (для PostgreSQL із pgvector)
- [Ollama](https://ollama.com) з моделями:
  `nomic-embed-text`, `bge-m3`, `qwen2.5:7b`, `llama3.1:8b`

## Запуск

```bash
docker compose up -d
mvn package
java -jar target/rag-bench-ua.jar fetch     # Корпус з укр. Вікіпедії -> data/corpus/
java -jar target/rag-bench-ua.jar qa        # Еталонні запитання -> data/qa.jsonl (ДАЛІ РУЧНА ВАЛІДАЦІЯ!)
java -jar target/rag-bench-ua.jar run       # Перебір сітки конфігурацій (--quick для проби)
java -jar target/rag-bench-ua.jar judge     # Оцінювання відповідей моделлю-суддею
java -jar target/rag-bench-ua.jar report    # Метрики -> results/summary.md, results.csv
```

Параметри сітки, моделі та підключення - в `experiment.properties`.
Перервані `run`/`judge` можна перезапускати: готові записи пропускаються.

## Структура результатів

- `data/qa.jsonl` - еталонний набір «запитання + відповідь + фрагмент-джерело» (після ручної валідації)
- `data/qa_orig.jsonl` - архів синтезованого набору до ручної валідації (для відтворюваності відбору)
- `results/runs.jsonl` - сирі записи прогонів (відповіді, знайдені фрагменти, затримки)
- `results/judgments.jsonl` - вердикти моделі-судді
- `results/summary.md`, `results/results.csv` - зведені метрики за конфігураціями

Ліцензія: [MIT](LICENSE)
