# DSHA 0.2.1-rc.3-20261027.1435-dsh0.2.1-rc.3 / build204

- Admission d’environnement : les requêtes de plugins, le démarrage de DSH et la création de terminal utilisent des leases d’environnement partagés ; l’analyse de la liste ne bloque plus le démarrage/arrêt de DSH ni l’ouverture/création du terminal.
- Requêtes en lecture seule : la liste vérifie seulement les outils prêts et n’installe/répare pas Python, pnpm ni les actifs gérés ; si les outils sont incomplets, l’ancien instantané est conservé.
- Priorité utilisateur : le démarrage de DSH annule les requêtes automatiques de plugins et utilise une file de scripts à haute priorité.
- Identité de build : versionCode 204 ; paquet de test dsh.android.client.SunsetRNE avec le certificat de test CI, pas E7E3.
- Terminal : le correctif du premier appui et dsh-web-mobile 3.2.2 intégré sont conservés.
