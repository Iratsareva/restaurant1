pipeline {
    agent any

    stages {

        stage('Build') {
            stages {
                stage('Build events-contract') {
                    steps {
                        dir('events-contract') {
                            sh 'chmod +x mvnw && ./mvnw clean install -DskipTests -B'
                        }
                    }
                }
                stage('Build restaurant_api_contracts') {
                    steps {
                        dir('restaurant_api_contracts') {
                            sh 'chmod +x mvnw && ./mvnw clean install -DskipTests -B'
                        }
                    }
                }
                stage('Build services') {
                    parallel {
                        stage('demo') {
                            steps {
                                dir('demo') {
                                    sh 'chmod +x mvnw && ./mvnw clean package -DskipTests -B'
                                }
                            }
                        }
                        stage('reservation-price-service') {
                            steps {
                                dir('reservation-price-service') {
                                    sh 'chmod +x mvnw && ./mvnw clean package -DskipTests -B'
                                }
                            }
                        }
                        stage('notification-service') {
                            steps {
                                dir('notification-service/notification-service') {
                                    sh 'chmod +x mvnw && ./mvnw clean package -DskipTests -B'
                                }
                            }
                        }
                        stage('audit-service') {
                            steps {
                                dir('audit-service/audit-service') {
                                    sh 'chmod +x mvnw && ./mvnw clean package -DskipTests -B'
                                }
                            }
                        }
                    }
                }
            }
        }

    }

    post {
        success {
            echo 'Build successful'
        }
        failure {
            echo 'Build failed'
            sh 'docker compose logs | tail -n 50 || true'
        }
    }
}