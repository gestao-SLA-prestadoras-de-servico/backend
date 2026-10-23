import base64
import hashlib
import json
import os
import secrets
from pathlib import Path

rabbitmq_user = os.environ["RABBITMQ_USER"]
rabbitmq_password = os.environ["RABBITMQ_PASSWORD"]


def generate_password_hash(password: str) -> str:
    salt = secrets.token_bytes(4)

    password_hash = hashlib.sha256(
        salt + password.encode("utf-8")
    ).digest()

    return base64.b64encode(salt + password_hash).decode("ascii")

rabbitmq_hash_password = generate_password_hash(rabbitmq_password)


definitions = {
    "users": [
        {
            "name": rabbitmq_user,
            "password_hash": rabbitmq_hash_password,
            "hashing_algorithm": "rabbit_password_hashing_sha256",
            "tags": ["administrator"],
        }
    ],
    "vhosts": [
        {
            "name": "/"
        }
    ],
    "permissions": [
        {
            "user": rabbitmq_user,
            "vhost": "/",
            "configure": ".*",
            "write": ".*",
            "read": ".*",
        }
    ],
    "exchanges": [
        {
            "name": "outbox.eventos",
            "vhost": "/",
            "type": "topic",
            "durable": True,
            "auto_delete": False,
            "internal": False,
            "arguments": {},
        }
    ],
}

output_path = Path("/rabbitmq/definitions.json")
output_path.parent.mkdir(parents=True, exist_ok=True)

output_path.write_text(
    json.dumps(definitions, indent=2),
    encoding="utf-8",
)

print(f"RabbitMQ definitions geradas em {output_path}")
print(f"Usuário configurado: {rabbitmq_user}")