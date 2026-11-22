# DSHA 0.2.1-rc.5-20261116.0740-dsh0.2.2-rc.1 / build369

- Migração por domínio para Kotlin: 6 classes de capabilities, 8 de montagem de comandos e pontos de montagem de runtime e 11 de gestão de plugins, 25 no total. Permissões, arranque e transações mantêm a semântica anterior. Não se promete ganho de desempenho; a divisão em módulos A8-P3 não faz parte desta versão.
- Os leitores dinâmicos geridos passam a apontar para ProotBootstrap.kt. Os caminhos de origem, testes de contrato e descriptor foram atualizados para evitar referências Java e resumos do gerador desatualizados.
- runtimeId: 1d42f79d5fea6bad5caaa2409822aa5949691c89e6e230b95011ae35db8a5757. baseVersion 10, DSH 0.2.2-rc.1 e formato de dados mantêm-se. No primeiro arranque após 368→369, prevê-se uma página de manutenção do ambiente em ecrã inteiro, uma única vez, preservando os dados pessoais.
- Rust continua com montagem prebuilt e backend ativo por predefinição, com provas de origem do mesmo run. Uma biblioteca indisponível seleciona JVM com motivo tipado; uma falha após selecionar Rust não é repetida nem uma divergência ocultada com resultados JVM.
- dsh-web-mobile 3.3.5 incluído e ativo por predefinição, intenção de desativação do utilizador, modo de segurança, Android 11+ / arm64, pacote e assinatura Mod mantêm-se; versionCode 369.
- O candidato integrado c243b7a0 passou todos os controlos de software e assinatura no dry-run 43823137009. A CI de publicação volta a verificar o commit final de build369. Esta versão ainda não tem aceitação em dispositivo; os registos de build368 não a substituem.
