# DSHA 0.2.1-rc.5-20261119.0400-dsh0.2.2-rc.5 / build370

- Le code de version passe à 370. Cette version Standard locale conserve le nom de paquet DSHA `dsh.android.client` et le certificat de signature E7E3 existant. Elle prend en charge les appareils arm64 sous Android 11 ou version ultérieure.
- Le DSH intégré passe à 0.2.2-rc.5 et l’interface mobile dsh-web-mobile à 3.3.5. Cette version intègre les mises à jour du projet amont relatives au démarrage, à la maintenance de l’environnement, aux extensions, aux sessions de terminal et aux sauvegardes automatiques.
- L’accès aux fichiers d’autres applications utilise le sélecteur de fichiers du système. La réduction et la révocation des autorisations de dossier sont mieux gérées lorsque l’enregistrement d’une autorisation échoue.
- Après une mise à jour depuis build308 avec le même paquet et le même certificat, le premier lancement peut nécessiter une maintenance de l’environnement d’exécution. L’application vérifie l’environnement existant et propose de le mettre à jour si nécessaire.
