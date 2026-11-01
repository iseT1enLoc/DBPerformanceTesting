#!/usr/bin/env python3
"""Generate the static benchmark dataset data/orders.csv.

Deterministic (fixed seed), so regenerating gives a byte-identical file.
Rows are grouped by customer: 50,000 customers x 20 orders = 1,000,000 rows by default.

Usage: python3 scripts/generate_orders_csv.py [--customers N] [--orders-per-customer N] [--out PATH]
"""
import argparse
import random
import string

ALPHABET = string.ascii_letters + string.digits

parser = argparse.ArgumentParser()
parser.add_argument("--customers", type=int, default=50_000)
parser.add_argument("--orders-per-customer", type=int, default=20)
parser.add_argument("--out", default="data/orders.csv")
parser.add_argument("--seed", type=int, default=42)
args = parser.parse_args()

rng = random.Random(args.seed)
with open(args.out, "w", newline="\n") as f:
    f.write("customerId,orderId,amount,payload\n")
    for c in range(args.customers):
        customer = f"c{c}"
        for o in range(args.orders_per_customer):
            amount = rng.uniform(0, 10_000)
            payload = "".join(rng.choices(ALPHABET, k=rng.randint(16, 32)))
            f.write(f"{customer},{o},{amount:.2f},{payload}\n")

print(f"Wrote {args.customers * args.orders_per_customer:,} rows to {args.out}")
