# DSHA 0.2.2-rc.5-20261119.0600-dsh0.2.2-rc.5 / build371

- L’application et le DSH intégré sont tous deux en version 0.2.2-rc.5. La version Standard locale utilise le paquet `dsh.android.client` et la signature E7E3 existante. Elle prend en charge les appareils arm64 sous Android 11 ou version ultérieure.
- Correction de la création d’une session de terminal lorsque la liste des arguments par défaut est vide.
- Si vous demandez un démarrage pendant une tâche liée à l’environnement, l’application attend la fin de la tâche puis poursuit le démarrage. Les opérations répétées de préparation de l’environnement et d’inscription des extensions ont été réduites.
- Réduction des redessins du fond pendant le défilement dans l’interface native et des traitements redondants pendant le défilement des pages web.
