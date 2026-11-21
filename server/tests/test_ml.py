"""Сервис машинного обучения: точность категоризации и прогнозирование."""
from app.ml import forecast
from app.ml.categorizer import normalize
from app.ml.control_set import CONTROL_SET
from app.services import ml_registry


def test_initial_model_meets_required_accuracy(db):
    model = ml_registry.active_model(db)
    assert model is not None and model.status == "ready"
    assert model.accuracy >= 0.80  # п. 4.3.1.3 ТЗ: точность на контрольной выборке
    assert 0 < model.unseen_accuracy < model.accuracy  # новые торговые точки сложнее
    assert 0 < model.precision <= 1 and 0 < model.recall <= 1 and 0 < model.f1 <= 1


def test_control_set_accuracy(make_api):
    """Не менее 50 операций с эталонными категориями; точность не ниже 80 %."""
    analyst = make_api("analyst")
    assert len(CONTROL_SET) >= 50
    result = analyst.post("/ml/evaluate", {"samples": CONTROL_SET})
    print(f"\nТочность на контрольной выборке: {result['accuracy']:.3f} "
          f"({len(CONTROL_SET) - len(result['errors'])} из {len(CONTROL_SET)}), "
          f"precision {result['precision']:.3f}, recall {result['recall']:.3f}, F1 {result['f1']:.3f}")
    for error in result["errors"]:
        print("  ошибка:", error)
    assert result["accuracy"] >= 0.80


def test_control_set_is_not_used_for_training(db, tmp_path):
    from app.ml import categorizer

    leaked = [{"text": c["text"], "kind": c["kind"], "category": "Прочее"} for c in CONTROL_SET]
    samples = ml_registry.collect_samples(db) + leaked
    metrics = categorizer.train(samples, str(tmp_path), 99)
    # Строки контрольной выборки отброшены: число примеров не выросло
    assert metrics["samples"] <= len(samples) - len(leaked)


def test_normalize_removes_noise():
    assert normalize("PYATEROCHKA 14872 MOSCOW RUS") == "pyaterochka moscow"
    assert normalize("Оплата *4821 ООО Ромашка") == "оплата ромашка"


def test_forecast_short_series_uses_moving_average():
    result = forecast.forecast_next([100, 120, 110])
    assert result["method"] == "moving_average"
    assert result["lower"] <= result["forecast"] <= result["upper"]


def test_forecast_medium_series_uses_exponential_smoothing():
    result = forecast.forecast_next([100, 105, 98, 110, 107, 103, 111, 109])
    assert result["method"] == "exponential_smoothing"
    assert 90 <= result["forecast"] <= 120
    assert result["lower"] < result["forecast"] < result["upper"]
    assert result["mape"] is not None


def test_forecast_long_series_accounts_for_seasonality():
    base = [100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 200]  # всплеск в декабре
    result = forecast.forecast_next(base * 3 + [100] * 11)
    assert result["method"] == "holt_winters"
    assert result["forecast"] > 150  # следующий месяц – декабрь


def test_forecast_empty_series():
    assert forecast.forecast_next([])["forecast"] == 0.0
