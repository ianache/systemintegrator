# Building

```
npx nx build bff --configuration=production --outputStyle=static
npx nx build integration-mfe --configuration=production --outputStyle=static
npx nx build shell --configuration=production --outputStyle=static
```


$env:PORT=4000 && npx nx serve bff --skip-nx-cache
npx nx serve integration-mfe
npx nx serve integration-mfe --skip-nx-cache
npx nx serve shell
npx nx serve shell --skip-nx-cache
```