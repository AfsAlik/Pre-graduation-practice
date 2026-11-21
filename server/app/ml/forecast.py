"""Прогнозирование расходов и доходов статистическими методами (п. 4.3.1.2 ТЗ).

В зависимости от длины временного ряда применяется:
  - экспоненциальное сглаживание с учетом сезонности (Хольта – Уинтерса) –
    при наличии не менее двух полных сезонных циклов (24 месяца);
  - простое экспоненциальное сглаживание – при наличии не менее 4 месяцев;
  - скользящее среднее – для коротких рядов.
Интервал уверенности рассчитывается по стандартному отклонению ошибок
одношагового прогноза на исторических данных.
"""
import math
import warnings

import numpy as np

Z_95 = 1.96
SEASON = 12


def _moving_average(values: list[float], window: int = 3) -> tuple[float, list[float]]:
    fitted = []
    for i in range(len(values)):
        part = values[max(0, i - window) : i]
        fitted.append(sum(part) / len(part) if part else values[i])
    tail = values[-window:]
    return sum(tail) / len(tail), fitted


def _exponential(values: list[float]) -> tuple[float, list[float]]:
    from statsmodels.tsa.holtwinters import SimpleExpSmoothing

    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        model = SimpleExpSmoothing(np.asarray(values, dtype=float), initialization_method="estimated")
        fit = model.fit()
    return float(fit.forecast(1)[0]), [float(v) for v in fit.fittedvalues]


def _holt_winters(values: list[float]) -> tuple[float, list[float]]:
    from statsmodels.tsa.holtwinters import ExponentialSmoothing

    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        model = ExponentialSmoothing(
            np.asarray(values, dtype=float), trend="add", seasonal="add",
            seasonal_periods=SEASON, initialization_method="estimated",
        )
        fit = model.fit()
    return float(fit.forecast(1)[0]), [float(v) for v in fit.fittedvalues]


def _fit(values: list[float]) -> tuple[float, list[float], str]:
    if len(values) >= 2 * SEASON:
        try:
            point, fitted = _holt_winters(values)
            return point, fitted, "holt_winters"
        except Exception:
            pass
    if len(values) >= 4:
        try:
            point, fitted = _exponential(values)
            return point, fitted, "exponential_smoothing"
        except Exception:
            pass
    point, fitted = _moving_average(values)
    return point, fitted, "moving_average"


def backtest_mape(values: list[float], steps: int = 3) -> float | None:
    """Средняя абсолютная процентная ошибка прогноза на последних месяцах ряда."""
    errors = []
    for i in range(steps, 0, -1):
        history, actual = values[:-i], values[-i]
        if len(history) < 2 or actual == 0:
            continue
        point, _, _ = _fit(history)
        errors.append(abs(point - actual) / abs(actual))
    return round(sum(errors) / len(errors) * 100, 2) if errors else None


def forecast_next(values: list[float]) -> dict:
    """Прогноз значения ряда на следующий период с интервалом уверенности 95 %."""
    values = [float(v) for v in values]
    if not values:
        return {"forecast": 0.0, "lower": 0.0, "upper": 0.0, "method": "none",
                "mape": None, "history_points": 0}
    if len(values) == 1:
        point, fitted, method = values[0], values[:], "last_value"
        sigma = abs(values[0]) * 0.25
    else:
        point, fitted, method = _fit(values)
        residuals = [a - f for a, f in zip(values[1:], fitted[1:], strict=False)]
        sigma = float(np.std(residuals, ddof=1)) if len(residuals) > 1 else abs(point) * 0.25
        if math.isnan(sigma):
            sigma = abs(point) * 0.25
    point = max(point, 0.0)
    return {
        "forecast": round(point, 2),
        "lower": round(max(point - Z_95 * sigma, 0.0), 2),
        "upper": round(point + Z_95 * sigma, 2),
        "method": method,
        "mape": backtest_mape(values),
        "history_points": len(values),
    }
