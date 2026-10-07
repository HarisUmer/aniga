#!/usr/bin/env python3
"""Mint an Aniga session token for a user id, the same way the backend does after Firebase sign-in.
For verification only: run on the server where .env holds ANIGA_SESSION_SECRET. Not shipped to clients."""
import base64, hashlib, hmac, json, sys, time, os

def b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()

def read_secret() -> str:
    env = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".env")
    for line in open(env):
        if line.startswith("ANIGA_SESSION_SECRET="):
            return line.split("=", 1)[1].strip()
    raise SystemExit("ANIGA_SESSION_SECRET not found in .env")

uid = sys.argv[1] if len(sys.argv) > 1 else "1"
secret = read_secret()
key = hashlib.sha256(secret.encode()).digest()
now = int(time.time())
header = {"alg": "HS256", "typ": "JWT"}
payload = {"sub": str(uid), "iss": "aniga", "iat": now, "exp": now + 3600}
signing_input = f"{b64(json.dumps(header, separators=(',', ':')).encode())}.{b64(json.dumps(payload, separators=(',', ':')).encode())}"
sig = hmac.new(key, signing_input.encode(), hashlib.sha256).digest()
print(f"{signing_input}.{b64(sig)}")
