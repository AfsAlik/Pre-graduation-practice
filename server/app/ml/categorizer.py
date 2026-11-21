"""Модель автоматической категоризации транзакций (п. 4.3.1.3 ТЗ).

Классификатор: TF-IDF по символьным n-граммам текста операции и логистическая
регрессия. Символьные n-граммы устойчивы к вариациям написания торговых точек
в банковских выписках (регистр, транслитерация, служебные суффиксы).
Качество оценивается на отложенной выборке по метрикам accuracy, precision,
recall и F1-score (п. 4.3.1.5 ТЗ).
"""
import os
import re
import threading
import time
from pathlib import Path

import joblib
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import accuracy_score, precision_recall_fscore_support
from sklearn.model_selection import GroupShuffleSplit
from sklearn.pipeline import Pipeline

from .control_set import CONTROL_SET

_cache: dict[str, tuple[float, Pipeline]] = {}
_lock = threading.Lock()
_NOISE = re.compile(r"[\d*#№]+|\b(rus|ru|ooo|ооо|ип|pos|sbp|sbol|retail)\b", re.IGNORECASE)


def normalize(text: str) -> str:
    """Приводит текст операции к нормальной форме: регистр, цифры, служебные слова."""
    text = _NOISE.sub(" ", text.lower().replace("ё", "е"))
    return re.sub(r"\s+", " ", text).strip()


def make_features(text: str, kind: str) -> str:
    return f"{kind} {normalize(text)}"


def model_path(models_dir: str, version: int) -> str:
    return str(Path(models_dir) / f"categorizer_v{version}.joblib")


def build_pipeline() -> Pipeline:
    return Pipeline(
        [
            ("tfidf", TfidfVectorizer(analyzer="char_wb", ngram_range=(2, 5), sublinear_tf=True)),
            ("clf", LogisticRegression(C=20.0, max_iter=2000)),
        ]
    )


def _scores(expected: list[str], predicted: list[str]) -> dict:
    precision, recall, f1, _ = precision_recall_fscore_support(
        expected, predicted, average="macro", zero_division=0
    )
    return {
        "accuracy": float(accuracy_score(expected, predicted)),
        "precision": float(precision),
        "recall": float(recall),
        "f1": float(f1),
    }


def train(samples: list[dict], models_dir: str, version: int, seed: int = 42,
          control: list[dict] | None = None) -> dict:
    """Обучает модель и сохраняет ее в файл. Возвращает показатели качества.

    samples – список словарей с ключами text, kind, category (и необязательным group).
    Качество оценивается двумя способами:
      - на контрольной выборке (control) – типичные операции с эталонными
        категориями, не участвующие в обучении; эти показатели являются основными;
      - на отложенных 20 % исходных строк обучающего набора (unseen_accuracy) –
        торговые точки, которых модель при обучении не видела вовсе; показатель
        характеризует способность модели к обобщению.
    Итоговая модель обучается на полном наборе данных.
    """
    started = time.monotonic()
    control = CONTROL_SET if control is None else control
    reserved = {normalize(c["text"]) for c in control}
    samples = [s for s in samples if normalize(s["text"]) not in reserved]
    texts = [make_features(s["text"], s["kind"]) for s in samples]
    labels = [s["category"] for s in samples]
    if len(set(labels)) < 2:
        raise ValueError("Для обучения необходимы примеры не менее чем двух категорий")
    # Вариации одной исходной строки (поле group) целиком попадают либо в обучающую,
    # либо в отложенную часть: иначе оценка обобщения была бы завышена.
    groups = [s.get("group") or normalize(s["text"]) for s in samples]
    splitter = GroupShuffleSplit(n_splits=1, test_size=0.2, random_state=seed)
    train_index, test_index = next(splitter.split(texts, labels, groups))
    pipeline = build_pipeline()
    pipeline.fit([texts[i] for i in train_index], [labels[i] for i in train_index])
    unseen = _scores([labels[i] for i in test_index],
                     list(pipeline.predict([texts[i] for i in test_index])))

    final = build_pipeline()
    final.fit(texts, labels)
    # Вид (доход или расход) каждой категории – используется при выборе ответа
    final.category_kinds_ = {s["category"]: s["kind"] for s in samples}
    os.makedirs(models_dir, exist_ok=True)
    path = model_path(models_dir, version)
    joblib.dump(final, path)

    metrics = evaluate(path, control) if control else dict(unseen)
    metrics.pop("errors", None)
    metrics.update(
        samples=len(samples),
        control_samples=len(control),
        categories=len(set(labels)),
        unseen_accuracy=unseen["accuracy"],
        unseen_f1=unseen["f1"],
        file_path=path,
        duration_seconds=round(time.monotonic() - started, 3),
    )
    return metrics


def load(path: str) -> Pipeline:
    """Загружает модель из файла с кешированием по времени изменения файла."""
    mtime = os.path.getmtime(path)
    with _lock:
        cached = _cache.get(path)
        if cached and cached[0] == mtime:
            return cached[1]
        pipeline = joblib.load(path)
        _cache[path] = (mtime, pipeline)
        return pipeline


def predict(path: str, items: list[dict]) -> list[dict]:
    """Определяет категории операций. items – словари с ключами text и kind.

    Модель обучается на категориях обоих видов, поэтому вероятности
    рассматриваются только по категориям того же вида, что и операция.
    """
    if not items:
        return []
    pipeline = load(path)
    features = [make_features(i["text"], i.get("kind", "expense")) for i in items]
    probabilities = pipeline.predict_proba(features)
    classes = list(pipeline.classes_)
    kinds = getattr(pipeline, "category_kinds_", None)
    result = []
    for item, row in zip(items, probabilities, strict=True):
        order = sorted(range(len(classes)), key=lambda i: row[i], reverse=True)
        best = order[0]
        if kinds:
            wanted = item.get("kind", "expense")
            best = next((i for i in order if kinds.get(classes[i]) == wanted), best)
        result.append({"category": classes[best], "confidence": float(row[best])})
    return result


def evaluate(path: str, samples: list[dict]) -> dict:
    """Оценивает модель на выборке с известными категориями."""
    predicted = [p["category"] for p in predict(path, samples)]
    expected = [s["category"] for s in samples]
    return {
        **_scores(expected, predicted),
        "samples": len(samples),
        "errors": [
            {"text": s["text"], "expected": e, "predicted": p}
            for s, e, p in zip(samples, expected, predicted, strict=True)
            if e != p
        ],
    }
