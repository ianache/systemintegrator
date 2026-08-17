# Caso de Integración: Extracción Delta de Clientes desde SAP HANA (`SBO_COMSATEL`)

Este documento especifica la configuración completa del **Perfil de Integración Declarativo (`IntegrationProfile`)** para el escenario de extracción por base de datos desde **SAP HANA B1 (`SBO_COMSATEL`)** utilizando el motor genérico `GenericJdbcAdapter` y la validación de seguridad AST `SqlSecurityValidator`.

---

## 1. Ficha Técnica de la Integración

| Parámetro | Valor | Descripción |
| --- | --- | --- |
| **Dominio de Negocio** | `customers` | Entidad canónica de Clientes |
| **Fuente Externa** | `sap-hana` | Base de Datos SAP HANA |
| **Dirección** | `INBOUND` | Extracción desde SAP HANA hacia la Plataforma |
| **Source of Truth** | `EXTERNAL` | SAP es la Fuente de Verdad para datos maestros de Clientes |
| **Protocolo / Conector** | `JDBC` / `generic-jdbc` | Motor de conexión relacional parametrizada |
| **Adaptador** | `generic-jdbc-adapter` | Adaptador genérico declarativo Zero-Code |
| **Endpoint (JDBC URL)** | `jdbc:sap://192.168.1.100:30015?databaseName=SBO_COMSATEL` | IP `192.168.1.100`, Puerto `30015` |
| **Credenciales (`credentialRef`)** | `secret/sap/sigo-hana-credentials` | Almacenado en Vault (User: `SIGO`, Pass: `CHANGEME`) |
| **Colección de Postman** | [`caso-integracion-sap-hana-customer.json`](file:///c:/Users/ianache/Desktop/DATA/01-DOCUMENTOS/03-PERSONAL/12-systemintegrator/docs/caso-integracion-sap-hana-customer.json) | Colección lista para importar en Postman v2.1 |
| **Frecuencia (`syncPolicy`)** | Cada 1 hora (`0 0 * * * *` / 3600000 ms) | Polling programado con *Overlap Buffer* |

---

## 2. Consulta SQL Delta Validada (`extractionConfig`)

La consulta SQL cumple estrictamente con el guardrail de seguridad **`SqlSecurityValidator`** (sentencia únicamente de tipo `SELECT`, sin concatenación de cadenas, sin multi-statements y parametrizada con el filtro delta de fecha `:last_date`):

```sql
SELECT
    -- Identificación y Datos Principales del Cliente
    T0."CardCode" AS CardCode,
    T0."CardName" AS CardName,
    T0."CardType" AS CardType,
    T0."LicTradNum" AS IdNumber,
    T0."E_Mail" AS EmailBP,
    T0."validFor" AS ValidFor,
    T0."U_EXX_TIPODOCU" AS DocumentType,
    T0."U_EXX_TIPOPERS" AS PersonType,
    T0."Phone1" AS Phone1,
    T0."Phone2" AS Phone2,
    T0."Cellular" AS Cellular,
    -- Datos de Dirección y Ubicación
    T1."Address" AS AddressName,
    COALESCE(T0."State1", T0."State2") AS State,
    T1."City" AS City,
    T1."County" AS Country,
    T1."Street" AS Street,
    T1."U_EXC_TIPDIR" AS AddressType,
    -- Timestamp para Watermark Delta
    GREATEST(T0."CreateDate", T0."UpdateDate") AS CreateUpdate
FROM SBO_COMSATEL.OCRD T0
LEFT JOIN SBO_COMSATEL.CRD1 T1 ON T0."CardCode" = T1."CardCode"
WHERE T0."CreateDate" >= :last_date OR T0."UpdateDate" >= :last_date
ORDER BY CreateUpdate ASC;
```

---

## 3. Payload JSON para la Creación del Perfil (`POST /api/v1/integration-profiles`)

Este es el payload en formato JSON para crear el perfil vía API Gateway (o cURL):

```json
{
  "businessDomain": "customers",
  "externalSource": "sap-hana",
  "syncDirection": "INBOUND",
  "sourceOfTruth": "EXTERNAL",
  "protocol": "JDBC",
  "connector": "generic-jdbc",
  "adapter": "generic-jdbc-adapter",
  "endpoint": "jdbc:sap://192.168.1.100:30015?databaseName=SBO_COMSATEL",
  "credentialRef": "secret/sap/sigo-hana-credentials",
  "extractionConfig": "{\"query\":\"SELECT T0.\\\"CardCode\\\" AS CardCode, T0.\\\"CardName\\\" AS CardName, T0.\\\"CardType\\\" AS CardType, T0.\\\"LicTradNum\\\" AS IdNumber, T0.\\\"E_Mail\\\" AS EmailBP, T0.\\\"validFor\\\" AS ValidFor, T0.\\\"U_EXX_TIPODOCU\\\" AS DocumentType, T0.\\\"U_EXX_TIPOPERS\\\" AS PersonType, T0.\\\"Phone1\\\" AS Phone1, T0.\\\"Phone2\\\" AS Phone2, T0.\\\"Cellular\\\" AS Cellular, T1.\\\"Address\\\" AS AddressName, COALESCE(T0.\\\"State1\\\", T0.\\\"State2\\\") AS State, T1.\\\"City\\\" AS City, T1.\\\"County\\\" AS Country, T1.\\\"Street\\\" AS Street, T1.\\\"U_EXC_TIPDIR\\\" AS AddressType, GREATEST(T0.\\\"CreateDate\\\", T0.\\\"UpdateDate\\\") AS CreateUpdate FROM SBO_COMSATEL.OCRD T0 LEFT JOIN SBO_COMSATEL.CRD1 T1 ON T0.\\\"CardCode\\\" = T1.\\\"CardCode\\\" WHERE T0.\\\"CreateDate\\\" >= :last_date OR T0.\\\"UpdateDate\\\" >= :last_date ORDER BY CreateUpdate ASC\",\"watermarkParam\":\"last_date\",\"keyColumn\":\"CardCode\",\"fetchSize\":1000}",
  "mapping": "{\"customerId\":\"CardCode\",\"legalName\":\"CardName\",\"cardType\":\"CardType\",\"taxId\":\"IdNumber\",\"email\":\"EmailBP\",\"status\":\"ValidFor\",\"documentType\":\"DocumentType\",\"personType\":\"PersonType\",\"phone\":{\"landline\":\"Phone1\",\"secondary\":\"Phone2\",\"mobile\":\"Cellular\"},\"address\":{\"name\":\"AddressName\",\"state\":\"State\",\"city\":\"City\",\"country\":\"Country\",\"street\":\"Street\",\"type\":\"AddressType\"},\"lastChangeDate\":\"CreateUpdate\"}",
  "syncPolicy": "{\"cronExpression\":\"0 0 * * * *\",\"overlapBufferSeconds\":300}"
}
```

---

## 4. Ejemplos de Invocación cURL / PowerShell

### cURL (Linux / Bash):
```bash
ACCESS_TOKEN='<your-jwt-access-token>'

curl -i -X POST http://localhost:8081/api/v1/integration-profiles \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  --data '{
    "businessDomain": "customers",
    "externalSource": "sap-hana",
    "syncDirection": "INBOUND",
    "sourceOfTruth": "EXTERNAL",
    "protocol": "JDBC",
    "connector": "generic-jdbc",
    "adapter": "generic-jdbc-adapter",
    "endpoint": "jdbc:sap://192.168.1.100:30015?databaseName=SBO_COMSATEL",
    "credentialRef": "secret/sap/sigo-hana-credentials",
    "extractionConfig": "{\"query\":\"SELECT T0.\\\"CardCode\\\" AS CardCode, T0.\\\"CardName\\\" AS CardName, T0.\\\"CardType\\\" AS CardType, T0.\\\"LicTradNum\\\" AS IdNumber, T0.\\\"E_Mail\\\" AS EmailBP, T0.\\\"validFor\\\" AS ValidFor, T0.\\\"U_EXX_TIPODOCU\\\" AS DocumentType, T0.\\\"U_EXX_TIPOPERS\\\" AS PersonType, T0.\\\"Phone1\\\" AS Phone1, T0.\\\"Phone2\\\" AS Phone2, T0.\\\"Cellular\\\" AS Cellular, T1.\\\"Address\\\" AS AddressName, COALESCE(T0.\\\"State1\\\", T0.\\\"State2\\\") AS State, T1.\\\"City\\\" AS City, T1.\\\"County\\\" AS Country, T1.\\\"Street\\\" AS Street, T1.\\\"U_EXC_TIPDIR\\\" AS AddressType, GREATEST(T0.\\\"CreateDate\\\", T0.\\\"UpdateDate\\\") AS CreateUpdate FROM SBO_COMSATEL.OCRD T0 LEFT JOIN SBO_COMSATEL.CRD1 T1 ON T0.\\\"CardCode\\\" = T1.\\\"CardCode\\\" WHERE T0.\\\"CreateDate\\\" >= :last_date OR T0.\\\"UpdateDate\\\" >= :last_date ORDER BY CreateUpdate ASC\",\"watermarkParam\":\"last_date\",\"keyColumn\":\"CardCode\",\"fetchSize\":1000}",
    "mapping": "{\"customerId\":\"CardCode\",\"legalName\":\"CardName\",\"cardType\":\"CardType\",\"taxId\":\"IdNumber\",\"email\":\"EmailBP\",\"status\":\"ValidFor\",\"documentType\":\"DocumentType\",\"personType\":\"PersonType\",\"phone\":{\"landline\":\"Phone1\",\"secondary\":\"Phone2\",\"mobile\":\"Cellular\"},\"address\":{\"name\":\"AddressName\",\"state\":\"State\",\"city\":\"City\",\"country\":\"Country\",\"street\":\"Street\",\"type\":\"AddressType\"},\"lastChangeDate\":\"CreateUpdate\"}",
    "syncPolicy": "{\"cronExpression\":\"0 0 * * * *\",\"overlapBufferSeconds\":300}"
  }'
```

### PowerShell:
```powershell
$headers = @{
    "Authorization" = "Bearer $env:ACCESS_TOKEN"
    "Content-Type"  = "application/json"
}

$body = @{
    businessDomain   = "customers"
    externalSource   = "sap-hana"
    syncDirection    = "INBOUND"
    sourceOfTruth    = "EXTERNAL"
    protocol         = "JDBC"
    connector        = "generic-jdbc"
    adapter          = "generic-jdbc-adapter"
    endpoint         = "jdbc:sap://192.168.1.100:30015?databaseName=SBO_COMSATEL"
    credentialRef    = "secret/sap/sigo-hana-credentials"
    extractionConfig = '{"query":"SELECT T0.\"CardCode\" AS CardCode, T0.\"CardName\" AS CardName, T0.\"CardType\" AS CardType, T0.\"LicTradNum\" AS IdNumber, T0.\"E_Mail\" AS EmailBP, T0.\"validFor\" AS ValidFor, T0.\"U_EXX_TIPODOCU\" AS DocumentType, T0.\"U_EXX_TIPOPERS\" AS PersonType, T0.\"Phone1\" AS Phone1, T0.\"Phone2\" AS Phone2, T0.\"Cellular\" AS Cellular, T1.\"Address\" AS AddressName, COALESCE(T0.\"State1\", T0.\"State2\") AS State, T1.\"City\" AS City, T1.\"County\" AS Country, T1.\"Street\" AS Street, T1.\"U_EXC_TIPDIR\" AS AddressType, GREATEST(T0.\"CreateDate\", T0.\"UpdateDate\") AS CreateUpdate FROM SBO_COMSATEL.OCRD T0 LEFT JOIN SBO_COMSATEL.CRD1 T1 ON T0.\"CardCode\" = T1.\"CardCode\" WHERE T0.\"CreateDate\" >= :last_date OR T0.\"UpdateDate\" >= :last_date ORDER BY CreateUpdate ASC","watermarkParam":"last_date","keyColumn":"CardCode","fetchSize":1000}'
    mapping          = '{"customerId":"CardCode","legalName":"CardName","cardType":"CardType","taxId":"IdNumber","email":"EmailBP","status":"ValidFor","documentType":"DocumentType","personType":"PersonType","phone":{"landline":"Phone1","secondary":"Phone2","mobile":"Cellular"},"address":{"name":"AddressName","state":"State","city":"City","country":"Country","street":"Street","type":"AddressType"},"lastChangeDate":"CreateUpdate"}'
    syncPolicy       = '{"cronExpression":"0 0 * * * *","overlapBufferSeconds":300}'
} | ConvertTo-Json -Depth 5

Invoke-RestMethod -Uri "http://localhost:8081/api/v1/integration-profiles" -Method Post -Headers $headers -Body $body
```

---

## 5. Evento Canónico de Salida Generado (`customer.updated`)

Al ejecutarse el motor `GenericJdbcAdapter`, cada fila leída de SAP HANA se transforma automáticamente al siguiente evento canónico y se registra en la tabla **`integration_outbox`** local para su publicación en Apache Kafka:

```json
{
  "eventId": "evt_c83d9a1f-829b-4e12-b11a-9f123d4e5678",
  "eventType": "customer.updated",
  "eventTimestamp": "2026-08-17T13:45:00Z",
  "tenantId": "11111111-1111-1111-1111-111111111111",
  "source": "sap-hana",
  "payload": {
    "customerId": "CLI-0001928",
    "legalName": "COMSATEL PERU S.A.C.",
    "cardType": "C",
    "taxId": "20100067891",
    "email": "facturacion@comsatel.com.pe",
    "status": "Y",
    "documentType": "6",
    "personType": "J",
    "phone": {
      "landline": "016140000",
      "secondary": null,
      "mobile": "999888777"
    },
    "address": {
      "name": "FISCAL",
      "state": "LIMA",
      "city": "LIMA",
      "country": "PE",
      "street": "AV. CORONEL PORTILLO 665",
      "type": "F"
    },
    "lastChangeDate": "2026-08-17T12:30:00Z"
  }
}
```
