# NetLens

**PT:** O NetLens será um app Android de diagnóstico de conexão simulada, feito para exercitar estados, falhas e resiliência com uma arquitetura que possa ser discutida em uma entrevista técnica.

**EN:** NetLens will be an Android app for simulated connection diagnostics, built to exercise state, failure, and resilience handling through an architecture suitable for a technical interview.

## Estado inicial / Initial state

Este commit contém somente o scaffold multi-módulo, o host Android mínimo, a fundação do design system e um esqueleto de CI. As telas, o domínio, a máquina de estados, persistência, rede e testes entram nos commits seguintes.

This commit contains only the multi-module scaffold, a minimal Android host, the design-system foundation, and a CI skeleton. Screens, domain behavior, the state machine, persistence, networking, and tests are added in later commits.

## Módulos / Modules

`:app` hospeda composição de DI e navegação. `:core:domain` é Kotlin/JVM puro; `:core:data` concentra a futura integração Room/Retrofit; `:core:designsystem` concentra o tema Compose; e os três módulos `:feature:*` reservam as telas do escopo.

`:app` hosts DI composition and navigation. `:core:domain` is pure Kotlin/JVM; `:core:data` will own the Room/Retrofit integration; `:core:designsystem` owns the Compose theme; and the three `:feature:*` modules reserve the scoped screens.

## Setup / Execução

Requer JDK 21 e Android SDK com a plataforma/API 36. O JDK 21 é o runtime e o bytecode alvo escolhidos para manter o build alinhado ao ambiente local. O minSdk 26 mantém a base de dispositivos ampla sem adicionar compatibilidade legada ao scaffold. O caminho local do SDK pode ser registrado em `local.properties`; esse arquivo é ignorado pelo Git.

Requires JDK 21 and Android SDK platform/API 36. JDK 21 is both the local runtime and bytecode target so the build stays aligned with the available environment. minSdk 26 keeps broad device coverage without adding legacy compatibility to the scaffold. The local SDK path can be recorded in `local.properties`; that file is ignored by Git.

```bash
./gradlew projects
./gradlew :app:assembleDebug
```

No Android Studio, abra a pasta do projeto, selecione um AVD com API 26 ou superior (por exemplo, `Medium_Phone_API_36.1`) e execute a configuração `app`.

In Android Studio, open the project folder, select an API 26+ AVD (for example, `Medium_Phone_API_36.1`), and run the `app` configuration.

Os testes e o lint completo serão conectados à CI quando as implementações e os testes determinísticos forem adicionados.

Full tests and lint will be wired into CI once the implementations and deterministic tests are added. Future UI validation will run through `./gradlew connectedAndroidTest` on that AVD.
