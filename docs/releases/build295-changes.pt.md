# DSHA 0.2.1-rc.5-20261103.1500-dsh0.2.1-rc.5 / build295

## Tablets, conversas longas e respostas em fluxo

- Compatibilidade SSE: tratamento correto de blocos de eventos sem data. Mantém a aceitação de JSON completo no EOF pelo SDK; JSON inválido continua a falhar explicitamente.
- Conversas longas evitam conversões e construção repetida de componentes. O estado das sessões é atualizado incrementalmente; a ordem das ramificações é preservada sem recursão profunda.
- O layout de tablet evita varreduras repetidas da página inteira. A alta taxa de atualização e a amostragem do tema reutilizam entradas iguais, mantendo a invalidação por foco, tela, configurações e geração da página.
- A inicialização reduz leituras repetidas do APK e importa módulos opcionais de Python sob demanda. Os limites de permissões, dados e erros permanecem.
- Os ambientes principal e de recuperação independente compartilham os mesmos bytes do arquivo para manter o tamanho do APK; diretórios e ciclos de execução continuam separados.
- Os testes de host, navegador e dispositivo são registrados separadamente. Consulte o relatório de entrega; não há promessa de aceleração fixa ou cobertura de todos os formatos históricos.
