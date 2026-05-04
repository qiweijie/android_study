package com.example.studydemo;

interface ICalculatorService {
    int add(int a, int b);
    int subtract(int a, int b);
    int multiply(int a, int b);
    String getServerProcessInfo();
    void simulateANR(int blockTimeMs);
}