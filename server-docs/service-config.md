> vim /etc/systemd/system/expense-api.service

```conf
[Unit]
Description=Expense API
Wants=network-online.target
After=network-online.target postgresql.service

[Service]
Type=simple
User=expense-api
Group=expense-api
EnvironmentFile=/var/www/html/expense-api/expense-api.env
WorkingDirectory=/var/www/html/expense-api
ExecStart=/usr/bin/java -jar /var/www/html/expense-api/target/expense.jar

Restart=on-failure
RestartSec=5
TimeoutStopSec=60
SuccessExitStatus=143

NoNewPrivileges=true
PrivateTmp=true
ProtectHome=true
ProtectSystem=full
UMask=0077

[Install]
WantedBy=multi-user.target
```

## expense.env

```.env
SPRING_PROFILES_ACTIVE=prod
DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/expense
DATABASE_USERNAME=expense_app
DATABASE_PASSWORD=
```

## "src/main/resources/application-prod.yml

```yml
server:
  address: 127.0.0.1
  port: #YOUR_PORT#
  forward-headers-strategy: native
  servlet:
    session:
      cookie:
        secure: true

expense:
  bootstrap:
    enabled: false
  recovery:
    enabled: false
  cors:
    allowed-origins: []

spring:
  datasource:
    url: ${DATABASE_URL}
    username: ${DATABASE_USERNAME}
    password: ${DATABASE_PASSWORD}
    hikari:
      maximum-pool-size: 5
      minimum-idle: 1
```

```sh
sudo systemctl daemon-reload
sudo systemctl reset-failed expense-api
sudo systemctl restart expense-api
sudo journalctl -u expense-api --since "1 minute ago" --no-pager
```