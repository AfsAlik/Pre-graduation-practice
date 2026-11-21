"""Хранилище файлов: фотографии чеков и экспортированные отчеты (п. 4.3.2.1 д ТЗ).

По умолчанию файлы сохраняются в локальном каталоге. При FH_STORAGE_PROVIDER=s3
используется S3-совместимое объектное хранилище (требуется пакет boto3).
"""
import os
import uuid
from pathlib import Path

from ..config import get_settings


def _s3():
    import boto3

    settings = get_settings()
    return boto3.client("s3", endpoint_url=settings.s3_endpoint or None,
                        aws_access_key_id=settings.s3_access_key,
                        aws_secret_access_key=settings.s3_secret_key)


def save(folder: str, filename: str, content: bytes) -> str:
    """Сохраняет файл и возвращает его ключ в хранилище."""
    settings = get_settings()
    extension = os.path.splitext(filename)[1].lower()[:10]
    key = f"{folder}/{uuid.uuid4().hex}{extension}"
    if settings.storage_provider == "s3":
        _s3().put_object(Bucket=settings.s3_bucket, Key=key, Body=content)
    else:
        path = Path(settings.storage_dir) / key
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
    return key


def load(key: str) -> bytes:
    settings = get_settings()
    if settings.storage_provider == "s3":
        return _s3().get_object(Bucket=settings.s3_bucket, Key=key)["Body"].read()
    root = Path(settings.storage_dir).resolve()
    path = (root / key).resolve()
    if root not in path.parents:
        raise FileNotFoundError(key)
    return path.read_bytes()
