# DSHA 0.2.1-rc.3-20261027.1435-dsh0.2.1-rc.3 / build204

- Admissão de ambiente: consultas de plugins, início do DSH e criação de terminal usam leases de ambiente compartilhados; a varredura da lista não bloqueia mais iniciar/parar o DSH nem abrir/criar terminal.
- Consultas somente leitura: a lista apenas verifica ferramentas prontas e não instala ou repara Python, pnpm ou ativos gerenciados; com ferramentas incompletas, mantém o snapshot anterior.
- Prioridade do usuário: iniciar o DSH cancela consultas automáticas de plugins e usa uma fila de script de alta prioridade.
- Identidade de build: versionCode 204; pacote de teste dsh.android.client.SunsetRNE com certificado de teste de CI, não E7E3.
- Terminal: a correção do primeiro toque e o dsh-web-mobile 3.2.2 embutido foram mantidos.
