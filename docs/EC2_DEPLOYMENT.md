# Deploying the payment service on EC2 (Amazon Linux 2023)

The pipeline (`.github/workflows/deploy.yml`) runs the tests on every PR and push to `main`. On a push to
`main` it builds the jar, copies it to `/opt/payment/app.jar` on the server and restarts the `payment`
systemd service. The server is set up once, by hand, as below.

| | |
|---|---|
| Server | `16.4.73.53` (Amazon Linux 2023, SSH user `ec2-user`) |
| App directory | `/opt/payment` (`app.jar` + `.env`) |
| Service | `payment` (`deploy/payment.service`) |
| Port | `8082` |

## 1. Security group

| Port | Source | Why |
|---|---|---|
| 22 | `0.0.0.0/0` (key-only SSH) | GitHub Actions runners have no fixed IPs |
| 8082 | API gateway server only (its security group or IP) | Payment API traffic |

Do not open 8082 to the internet: the gateway adds the identity headers this service trusts.

## 2. Connect

```bash
chmod 400 payment-key.pem
ssh -i payment-key.pem ec2-user@16.4.73.53
```

## 3. Install Java 25

```bash
sudo dnf update -y
sudo dnf install -y java-25-amazon-corretto-headless
java -version          # must print 25
which java             # must be /usr/bin/java (the unit file uses this path)
```

If the package is not found, install Corretto 25 from Amazon's RPM:

```bash
sudo rpm --import https://yum.corretto.aws/corretto.key
sudo curl -Lo /etc/yum.repos.d/corretto.repo https://yum.corretto.aws/corretto.repo
sudo dnf install -y java-25-amazon-corretto-headless
```

On a small instance (1 GB RAM, e.g. t3.micro) add swap so the JVM is not killed:

```bash
sudo dd if=/dev/zero of=/swapfile bs=1M count=2048
sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile swap swap defaults 0 0' | sudo tee -a /etc/fstab
```

## 4. Service user and directory

```bash
sudo useradd --system --shell /sbin/nologin payment
sudo mkdir -p /opt/payment
# ec2-user owns the directory so the pipeline can scp the jar; the payment group can read it
sudo chown ec2-user:payment /opt/payment
sudo chmod 750 /opt/payment
```

## 5. Configuration (`/opt/payment/.env`)

```bash
sudo nano /opt/payment/.env
```

```bash
PORT=8082

# Neon PostgreSQL (JDBC form; user and password separate). If Flyway hangs at startup through the
# pooler, use the direct host (drop "-pooler" from the hostname).
DB_URL=jdbc:postgresql://ep-muddy-sound-axfk8t0z-pooler.c-4.us-east-2.aws.neon.tech/payment?sslmode=require&channelBinding=require
DB_USER=neondb_owner
DB_PASS=<neon password>

# Shared secret with the API gateway, and the auth service's JWKS
PAYMENT_GATEWAY_SECRET=<same value as the gateway>
AUTH_JWKS_URL=<auth service JWKS URL>

# Razorpay (rzp_test_... keys with RAZORPAY_LIVE=false; rzp_live_... keys only with RAZORPAY_LIVE=true)
PAYMENT_PROVIDER=razorpay
RAZORPAY_KEY_ID=<key id>
RAZORPAY_KEY_SECRET=<key secret>
RAZORPAY_WEBHOOK_SECRET=<webhook secret>
RAZORPAY_LIVE=false
RAZORPAY_ACCOUNT_ID=

# Browser redirect after checkout
CHECKOUT_SUCCESS_URL=<frontend URL>/payments/result

# Hospital seat payments -> hospital service callback (same secret as hospital's PAYMENT_WEBHOOK_SECRET)
HOSPITAL_SEAT_PAYMENT_WEBHOOK_URL=http://<hospital server IP>:8081/api/v1/hospital/seats/payments/webhook
HOSPITAL_SEAT_PAYMENT_WEBHOOK_SECRET=<shared secret>

# Teleconsultation (TCS) appointments -> TCS callback (same secret configured on the TCS side).
# TELECONSULTATION payments are refused until the URL is set. Return URL optional (default CHECKOUT_SUCCESS_URL).
TELECONSULT_PAYMENT_WEBHOOK_URL=http://<tcs server IP>:<port>/api/teleconsultation/payments/webhook
TELECONSULT_PAYMENT_WEBHOOK_SECRET=<shared secret>
TELECONSULT_PAYMENT_RETURN_URL=
```

```bash
sudo chown root:payment /opt/payment/.env
sudo chmod 640 /opt/payment/.env
```

## 6. Install the systemd service

From your machine (repo root):

```bash
scp -i payment-key.pem deploy/payment.service ec2-user@16.4.73.53:/tmp/
```

On the server:

```bash
sudo mv /tmp/payment.service /etc/systemd/system/payment.service
sudo restorecon /etc/systemd/system/payment.service 2>/dev/null || true
sudo systemctl daemon-reload
sudo systemctl enable payment
```

Do not start it yet: there is no jar until the first deploy.

## 7. GitHub secrets

Repo `uyirhealthsystem/Payment-service` → Settings → Secrets and variables → Actions → New repository secret:

| Name | Value |
|---|---|
| `EC2_HOST` | `16.4.73.53` |
| `EC2_USER` | `ec2-user` |
| `EC2_SSH_KEY` | full contents of `payment-key.pem`, including the `BEGIN`/`END` lines |

`ec2-user` already has passwordless sudo on Amazon Linux, which the restart step needs.

## 8. First deploy

Merge to `main` (or push to it). In the repo's Actions tab, the `test` job runs, then `deploy` copies the jar,
restarts the service and waits up to 60 s for `/actuator/health/readiness` (which includes the database).

## 9. Check it on the server

```bash
systemctl status payment
curl http://127.0.0.1:8082/actuator/health/readiness     # {"status":"UP"}
sudo journalctl -u payment -f                             # live logs
```

## Troubleshooting

| Symptom | Fix |
|---|---|
| `scp: Permission denied` | `sudo chown ec2-user:payment /opt/payment` |
| `ssh: handshake failed` in Actions | `EC2_SSH_KEY` must be the whole PEM; port 22 must be open |
| Service exits, `UnsupportedClassVersionError` | Java on the server is older than 25 (step 3) |
| Readiness never `UP`, DB errors in logs | Check `DB_URL` / `DB_USER` / `DB_PASS`; try the non-pooler Neon host |
| `Live keys refused` / `test keys refused` | `RAZORPAY_LIVE` does not match the key type |
| Killed with no error | Out of memory: add swap (step 3) or a larger instance |

Roll back: re-run the deploy job of an earlier commit from the Actions tab.
