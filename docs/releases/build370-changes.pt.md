# DSHA 0.2.1-rc.5-20261119.0400-dsh0.2.2-rc.5 / build370

- O código da versão passou a 370. Esta compilação Standard local mantém o pacote DSHA `dsh.android.client` e o certificado de assinatura E7E3 existente. É compatível com dispositivos arm64 com Android 11 ou posterior.
- O DSH incluído foi atualizado para 0.2.2-rc.5 e a interface móvel dsh-web-mobile para 3.3.5. A compilação inclui atualizações na inicialização, na manutenção do ambiente de execução, nos plugins, nas sessões do terminal e nos backups automáticos.
- O acesso a arquivos de outros apps usa o seletor de arquivos do sistema. Foi melhorado o tratamento da redução ou revogação de permissões de pasta quando não é possível salvar uma autorização.
- Após atualizar sobre a build308 com o mesmo pacote e certificado, pode ser necessário fazer a manutenção do ambiente de execução na primeira abertura. O app verifica o ambiente existente e oferece a opção de atualizá-lo quando necessário.
